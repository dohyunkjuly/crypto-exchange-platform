package com.exchange.spot.copy.copy;

import com.exchange.common.id.Ulid;
import com.exchange.common.money.Money;
import com.exchange.copy.follow.CopyCloseService;
import com.exchange.copy.settings.CopyQuoteAsset;
import com.exchange.copy.follow.CloseReason;
import com.exchange.copy.follow.CopyFollow;
import com.exchange.copy.follow.CopyFollowRepository;
import com.exchange.copy.follow.FollowStatus;
import com.exchange.copy.leadorder.CopyLeadOrder;
import com.exchange.copy.order.CopyOrder;
import com.exchange.copy.order.CopyOrderReason;
import com.exchange.copy.order.CopyOrderRepository;
import com.exchange.copy.order.CopyOrderStatus;
import com.exchange.copy.settings.CopySettings;
import com.exchange.copy.settings.CopySettingsRepository;
import com.exchange.spot.copy.copy.CopySizing.Fail;
import com.exchange.spot.copy.copy.CopySizing.Place;
import com.exchange.spot.copy.copy.CopySizing.Plan;
import com.exchange.spot.copy.copy.CopySizing.Skip;
import com.exchange.spot.order.OrderService;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.user.eligibility.EligibilityService;
import com.exchange.user.identity.UserRepository;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Places a closed lead order's copies, and keeps each copy's outcome
 * (docs/reference/copy-trading.md).
 *
 * <p><b>Once per follower.</b> The copy's row is written in the transaction that places its
 * order ({@code OrderService.placeCopyOrder}), and the table refuses a second row for the same
 * lead order and follow. A copy that could not be placed gets its row too, so an event
 * delivered twice finds one either way and does nothing.
 *
 * <p><b>Not retried.</b> A copy that fails is recorded and counted; at the limit the account is
 * closed. A sell with nothing to sell is neither a failure nor a success.
 *
 * <p><b>In a new random order every time, several at once.</b> Copies go in one after another,
 * and each buy moves the price for the next, so whoever comes last finds the least inside the
 * cap. Shuffling spreads that over every follower instead of the same ones each time, and
 * placing {@link #PARALLELISM} at once shortens the time the price has to move. Each copy is
 * its own transaction on its own follower's rows, so nothing is shared between them.
 *
 * <p><b>It reads balances settlement writes.</b> A leader who buys and sells within the moment
 * settlement takes to apply the buy is copied against a balance that has not caught up: the
 * sell finds nothing, and is skipped. Known, and accepted for now.
 */
@Service
public class CopyService {

    private static final Logger log = LoggerFactory.getLogger(CopyService.class);

    /** Below the database pool (Hikari's default of ten), so the listener keeps a connection
     *  for its own reads while copies are being placed. */
    static final int PARALLELISM = 8;

    private final CopyFollowRepository follows;
    private final CopyOrderRepository copyOrders;
    private final CopySettingsRepository settings;
    private final SpotSymbolRepository markets;
    private final BalanceRepository balances;
    private final UserRepository users;
    private final EligibilityService eligibility;
    private final OrderService orderService;
    private final CopyCloseService closes;
    private final CopyQuoteAsset quoteAsset;

    public CopyService(CopyFollowRepository follows, CopyOrderRepository copyOrders, CopySettingsRepository settings,
                       SpotSymbolRepository markets, BalanceRepository balances,
                       UserRepository users, EligibilityService eligibility, OrderService orderService,
                       CopyCloseService closes, CopyQuoteAsset quoteAsset) {
        this.follows = follows;
        this.copyOrders = copyOrders;
        this.settings = settings;
        this.markets = markets;
        this.balances = balances;
        this.users = users;
        this.eligibility = eligibility;
        this.orderService = orderService;
        this.closes = closes;
        this.quoteAsset = quoteAsset;
    }

    /** Every active follower of the lead order's leader gets its copy, unless it already has one. */
    public void copy(CopyLeadOrder lead) {
        Optional<SpotSymbol> market = markets.findById(lead.getSymbolId());
        if (market.isEmpty()) {
            log.error("lead order {} is on market {}, which does not exist; nobody copies it",
                    lead.getOrderId(), lead.getSymbolId());
            return;
        }
        CopySettings set = settings();
        List<CopyFollow> inTurn = new ArrayList<>(
                follows.findByLeaderIdAndStatus(lead.getLeaderId(), FollowStatus.ACTIVE));
        Collections.shuffle(inTurn);

        Semaphore slots = new Semaphore(PARALLELISM);
        List<Future<?>> placing = new ArrayList<>(inTurn.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (CopyFollow follow : inTurn) {
                placing.add(executor.submit(() -> {
                    slots.acquireUninterruptibly();
                    try {
                        if (!copyOrders.existsByLeadOrderIdAndFollowId(lead.getOrderId(), follow.getId())) {
                            copyFor(follow, lead, market.get(), set.getFailureLimit());
                        }
                    } finally {
                        slots.release();
                    }
                }));
            }
        }
        rethrowFirstFailure(placing);
    }

    /**
     * Every copy has finished by now (closing the executor waits). One that broke for a reason
     * other than the order path refusing it goes up, so the batch is read again; the copies that
     * were placed have their rows and are skipped then.
     */
    private static void rethrowFirstFailure(List<Future<?>> placing) {
        for (Future<?> copy : placing) {
            try {
                copy.get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while placing copies", e);
            }
        }
    }

    private void copyFor(CopyFollow follow, CopyLeadOrder lead, SpotSymbol market, int failureLimit) {
        Plan plan = mayTrade(follow)
                ? CopySizing.plan(follow, lead, market, available(follow, quoteAsset.id()),
                        available(follow, market.getBaseAssetId()), follow.getSlippageCapBps())
                : new Fail(CopyOrderReason.NOT_ELIGIBLE);
        switch (plan) {
            case Skip skip -> record(follow, lead, market, CopyOrderStatus.SKIPPED, skip.reason());
            case Fail fail -> {
                record(follow, lead, market, CopyOrderStatus.FAILED, fail.reason());
                countFailure(follow.getId(), failureLimit);
            }
            case Place place -> place(follow, lead, market, place, failureLimit);
        }
    }

    private void place(CopyFollow follow, CopyLeadOrder lead, SpotSymbol market, Place order, int failureLimit) {
        try {
            orderService.placeCopyOrder(follow.getCopyUserId(), follow.getUserId(), market.getSymbol(), order.side(),
                    order.type(), order.timeInForce(), order.price() == null ? null : Money.toDecimal(order.price()),
                    Money.toDecimal(order.quantity()),
                    made -> copyOrders.save(new CopyOrder(Ulid.randomUuid(), follow.getId(), lead.getOrderId(),
                            made.order().getId(), market.getId(), order.side(), CopyOrderStatus.PLACED, null)));
        } catch (DataIntegrityViolationException e) {
            // The row was there after all: this event came twice, and the first one won. Nothing was placed.
            log.info("lead order {} is already copied for follow {}", lead.getOrderId(), follow.getId());
        } catch (RuntimeException e) {
            // Anything that is not the order path refusing the order is not a failed copy:
            // it goes up, the batch is handed back, and the copy is tried again.
            CopyOrderReason reason = CopyOrderReason.ofRefusal(e).orElseThrow(() -> e);
            if (reason == CopyOrderReason.MARKET_PAUSED) {
                // Nobody's fault and nothing to learn from: not counted against the follow.
                record(follow, lead, market, CopyOrderStatus.SKIPPED, reason);
                return;
            }
            record(follow, lead, market, CopyOrderStatus.FAILED, reason);
            countFailure(follow.getId(), failureLimit);
        }
    }

    /** A copy with no order behind it: skipped, or refused before it could be placed. */
    private void record(CopyFollow follow, CopyLeadOrder lead, SpotSymbol market, CopyOrderStatus status,
                        CopyOrderReason reason) {
        copyOrders.save(new CopyOrder(Ulid.randomUuid(), follow.getId(), lead.getOrderId(), null, market.getId(),
                lead.getSide(), status, reason));
    }

    /** A copy's order traded: it worked, and the follow's count of failures starts again. */
    public void filled(CopyOrder copy) {
        if (copy.getStatus() != CopyOrderStatus.PLACED) {
            return;
        }
        copy.setStatus(CopyOrderStatus.FILLED);
        copyOrders.save(copy);
        if (copy.getLeadOrderId() != null) {
            follows.clearFailures(copy.getFollowId());
        }
    }

    /**
     * A copy's order closed with nothing filled. Only a copy still waiting on its outcome
     * changes, so the same event twice counts one failure. A closing sale that does not fill
     * is recorded and not counted: the account is closing already.
     */
    public void notFilled(CopyOrder copy, CopyOrderReason reason) {
        if (copy.getStatus() != CopyOrderStatus.PLACED) {
            return;
        }
        copy.setStatus(CopyOrderStatus.FAILED);
        copy.setReason(reason);
        copyOrders.save(copy);
        if (copy.getLeadOrderId() != null) {
            countFailure(copy.getFollowId(), settings().getFailureLimit());
        }
    }

    private void countFailure(UUID followId, int limit) {
        if (follows.countFailure(followId) == 0) {
            return; // not active any more
        }
        int inRow = follows.findById(followId).map(CopyFollow::getFailuresInRow).orElse(0);
        if (inRow >= limit && closes.begin(followId, CloseReason.FAILURES)) {
            log.info("follow {} closed after {} failed copies in a row", followId, inRow);
        }
    }

    private boolean mayTrade(CopyFollow follow) {
        return users.findById(follow.getCopyUserId()).map(eligibility::canTrade).orElse(false);
    }

    private long available(CopyFollow follow, Long assetId) {
        return balances.findByUserIdAndAssetId(follow.getCopyUserId(), assetId).map(Balance::getAvailable).orElse(0L);
    }

    private CopySettings settings() {
        return settings.findById(CopySettings.SINGLETON_ID).orElseThrow();
    }
}
