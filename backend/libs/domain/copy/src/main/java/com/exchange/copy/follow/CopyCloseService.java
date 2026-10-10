package com.exchange.copy.follow;

import com.exchange.copy.settings.CopyQuoteAsset;
import com.exchange.copy.share.CopyProfitShareService;
import com.exchange.copy.share.ShareTrigger;
import com.exchange.common.id.Ulid;
import com.exchange.common.money.Money;
import com.exchange.copy.leader.exception.LeadAccountNotFlatException;
import com.exchange.copy.follow.CloseReason;
import com.exchange.copy.follow.CopyFollow;
import com.exchange.copy.follow.CopyFollowRepository;
import com.exchange.copy.follow.FollowStatus;
import com.exchange.copy.order.CopyOrder;
import com.exchange.copy.order.CopyOrderReason;
import com.exchange.copy.order.CopyOrderRepository;
import com.exchange.copy.order.CopyOrderStatus;
import com.exchange.copy.settings.CopySettings;
import com.exchange.copy.settings.CopySettingsRepository;
import com.exchange.spot.order.OrderService;
import com.exchange.spot.order.OrderStatus;
import com.exchange.spot.order.OrderType;
import com.exchange.spot.order.Side;
import com.exchange.spot.order.SpotOrderRepository;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.spot.trade.SpotTrade;
import com.exchange.spot.trade.SpotTradeRepository;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceRepository;
import com.exchange.wallet.transfer.TransferService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closing a copy account (docs/reference/copy-trading.md): sell what it holds, and once that
 * has settled, return everything to the follower.
 *
 * <p>Two steps, because the second cannot be done from the first. A sale is an order: its
 * proceeds reach the account when settlement has applied the fill, on its own clock. So
 * {@link #begin} places the sales and {@link #finishIfSettled} is asked again and again until
 * the account has no order open and nothing locked -- only then is what it holds everything
 * it will ever hold. Returning funds on the sale's event instead would leave the proceeds
 * behind in an account marked closed.
 */
@Service
public class CopyCloseService {

    private static final List<OrderStatus> OPEN = List.of(OrderStatus.PENDING_TRIGGER, OrderStatus.NEW,
            OrderStatus.PARTIALLY_FILLED, OrderStatus.PENDING_CANCEL);

    private final CopyFollowRepository follows;
    private final CopyOrderRepository copyOrders;
    private final CopySettingsRepository settings;
    private final BalanceRepository balances;
    private final SpotSymbolRepository markets;
    private final SpotTradeRepository trades;
    private final SpotOrderRepository orders;
    private final OrderService orderService;
    private final TransferService transfers;
    private final CopyQuoteAsset quoteAsset;
    private final CopyProfitShareService shares;

    public CopyCloseService(CopyFollowRepository follows, CopyOrderRepository copyOrders,
                            CopySettingsRepository settings, BalanceRepository balances,
                            SpotSymbolRepository markets, SpotTradeRepository trades, SpotOrderRepository orders,
                            OrderService orderService, TransferService transfers, CopyQuoteAsset quoteAsset,
                            CopyProfitShareService shares) {
        this.shares = shares;
        this.follows = follows;
        this.copyOrders = copyOrders;
        this.settings = settings;
        this.balances = balances;
        this.markets = markets;
        this.trades = trades;
        this.orders = orders;
        this.orderService = orderService;
        this.transfers = transfers;
        this.quoteAsset = quoteAsset;
    }

    /**
     * Stops the copying and sells what the account holds. Whoever moves the row from ACTIVE
     * does the selling; a second caller finds it already closing and does nothing.
     *
     * @return true for the call that started it
     */
    public boolean begin(UUID followId, CloseReason reason) {
        if (follows.startClosing(followId, reason) == 0) {
            return false;
        }
        CopyFollow follow = follows.findById(followId).orElseThrow();
        long threshold = settings.findById(CopySettings.SINGLETON_ID).orElseThrow().getCloseSellThreshold();
        for (Balance held : balances.findByUserId(follow.getCopyUserId())) {
            if (held.getAvailable() <= 0 || held.getAssetId().equals(quoteAsset.id())) {
                continue;
            }
            markets.findByBaseAssetIdAndQuoteAssetId(held.getAssetId(), quoteAsset.id())
                    .ifPresent(market -> sell(follow, market, held.getAvailable(), threshold));
        }
        return true;
    }

    /**
     * A leader may stop only once the lead account is flat: no order open, and nothing held but
     * the copy asset and dust. Each sale the leader makes on the way there is copied, so the
     * followers' accounts are sold down at the leader's own pace rather than all at once when
     * the leader goes; by the time the leader can stop, their close has mostly money to return.
     *
     * @throws LeadAccountNotFlatException an order is open, or a holding is worth selling
     */
    public void requireFlat(Long leadUserId) {
        if (orders.countByUserIdAndStatusIn(leadUserId, OPEN) > 0) {
            throw new LeadAccountNotFlatException("the lead account has an order open");
        }
        long threshold = settings.findById(CopySettings.SINGLETON_ID).orElseThrow().getCloseSellThreshold();
        for (Balance held : balances.findByUserId(leadUserId)) {
            if (held.getTotal() <= 0 || held.getAssetId().equals(quoteAsset.id())) {
                continue;
            }
            boolean sellable = markets.findByBaseAssetIdAndQuoteAssetId(held.getAssetId(), quoteAsset.id())
                    .map(market -> sellable(market, held.getTotal(), threshold)).orElse(false);
            if (sellable) {
                throw new LeadAccountNotFlatException("the lead account still holds a coin worth selling");
            }
        }
    }

    /** A holding on the market's step that the market takes, worth at least the threshold at the last trade. */
    private boolean sellable(SpotSymbol market, long held, long threshold) {
        long step = market.getMarketQtyStep() != null ? market.getMarketQtyStep() : market.getQtyStep();
        long quantity = held - held % step;
        if (!market.isValidQuantity(true, quantity)) {
            return false;
        }
        Optional<Long> price = trades.lastBefore(market.getId(), Instant.now()).map(SpotTrade::getPrice);
        return price.isEmpty() || Money.multiply(price.get(), quantity) >= threshold;
    }

    /** At market, the whole holding on the market's step. What is left over, or too small to sell, is returned as it is. */
    private void sell(CopyFollow follow, SpotSymbol market, long held, long threshold) {
        if (!sellable(market, held, threshold)) {
            return;
        }
        long step = market.getMarketQtyStep() != null ? market.getMarketQtyStep() : market.getQtyStep();
        long quantity = held - held % step;
        try {
            orderService.placeCopyOrder(follow.getCopyUserId(), follow.getUserId(), market.getSymbol(), Side.SELL,
                    OrderType.MARKET, null, null, Money.toDecimal(quantity),
                    made -> copyOrders.save(new CopyOrder(Ulid.randomUuid(), follow.getId(), null,
                            made.order().getId(), market.getId(), Side.SELL, CopyOrderStatus.PLACED, null)));
        } catch (RuntimeException e) {
            // A market that will not take the sale leaves the holding to be returned as it is.
            CopyOrderReason reason = CopyOrderReason.ofRefusal(e).orElseThrow(() -> e);
            copyOrders.save(new CopyOrder(Ulid.randomUuid(), follow.getId(), null, null, market.getId(), Side.SELL,
                    CopyOrderStatus.FAILED, reason));
        }
    }

    /**
     * Returns everything to the follower and marks the account closed, if it is closing and has
     * settled: no order open, nothing locked. One transaction, so the funds and the status move
     * together.
     *
     * @return true if this call closed it
     */
    @Transactional
    public boolean finishIfSettled(UUID followId) {
        CopyFollow follow = follows.findById(followId).orElseThrow();
        if (follow.getStatus() != FollowStatus.CLOSING
                || orders.countByUserIdAndStatusIn(follow.getCopyUserId(), OPEN) > 0) {
            return false;
        }
        if (balances.findByUserId(follow.getCopyUserId()).stream().anyMatch(balance -> balance.getLocked() > 0)) {
            return false;
        }
        // Everything is sold and settled, so this is the last profit the account will realize:
        // the leader's share of it leaves before the rest goes back.
        shares.settle(followId, ShareTrigger.CLOSE, null);
        for (Balance balance : balances.findByUserId(follow.getCopyUserId())) {
            if (balance.getAvailable() > 0) {
                transfers.transfer(Ulid.randomUuid(), follow.getCopyUserId(), follow.getUserId(),
                        balance.getAssetId(), balance.getAvailable(), follow.getUserId());
            }
        }
        follow.setStatus(FollowStatus.CLOSED);
        follow.setClosedAt(Instant.now());
        follows.save(follow);
        return true;
    }
}
