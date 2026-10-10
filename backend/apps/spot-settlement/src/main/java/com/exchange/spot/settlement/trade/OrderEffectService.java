package com.exchange.spot.settlement.trade;

import com.exchange.common.money.Money;
import com.exchange.common.id.Ulid;
import com.exchange.contracts.spot.v1.OrderAmendRejected;
import com.exchange.contracts.spot.v1.OrderAmended;
import com.exchange.contracts.spot.v1.OrderCanceled;
import com.exchange.contracts.spot.v1.OrderRejected;
import com.exchange.contracts.spot.v1.TradeExecuted;
import com.exchange.spot.order.OrderLock;
import com.exchange.spot.order.OrderStatus;
import com.exchange.spot.order.SpotOrder;
import com.exchange.spot.order.SpotOrderRepository;
import com.exchange.spot.orderfilllog.SpotOrderFillLog;
import com.exchange.spot.orderfilllog.SpotOrderFillLogRepository;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.trade.SpotTrade;
import com.exchange.spot.trade.SpotTradeRepository;
import com.exchange.wallet.ledger.ReferenceType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one trading event's effect on {@link SpotOrder}/{@link SpotTrade} and returns
 * the wallet balance mutations it implies -- those are applied later, deferred and
 * grouped across the whole batch by {@code (userId, assetId)}, by {@link
 * BalanceEffectService}. This class never calls {@code BalanceService} directly.
 *
 * <p>The trade side ({@link #recordTrades}) and the order side ({@link
 * #applyFillsForOrder}) are two separate methods, deliberately, each with its own
 * idempotency mechanism -- they used to be one method gated by one check, and that was
 * a real bug (see {@link #recordTrades}'s own javadoc for the failure mode it caused).
 * {@code recordTrades}' {@code SpotTrade} rows answer "has this trade happened at all";
 * {@link SpotOrderFillLog} answers "has this SPECIFIC order already counted it" -- a
 * different question the moment one trade's two sides can land in two entirely separate
 * aggregate writes rather than together. Balance mutations are gated by neither -- see
 * {@link #recordTrades}.
 *
 * <p>{@link #applyFillsForOrder} takes every fill destined for ONE order and applies
 * them as a SINGLE aggregate write, not one write per trade -- the database can still
 * only write to one row at a time no matter how many fills arrive "at once", so a large
 * order matched against many counterparties collapses its own repeated writes into one
 * instead of paying a round trip per fill. Safe to call for many different orders
 * concurrently (each touches only its own row and its own {@link SpotOrderFillLog}
 * rows); the aggregate write itself is still a single atomic increment ({@link
 * SpotOrderRepository#applyFillAtomically}), never a read-then-write -- see that
 * method's own javadoc for why that stays correct even if this were ever called
 * concurrently for the same order too.
 */
@Service
public class OrderEffectService {

    private static final Logger log = LoggerFactory.getLogger(OrderEffectService.class);

    private final SpotOrderRepository spotOrders;
    private final SpotTradeRepository spotTrades;
    private final SpotOrderFillLogRepository fillLogs;
    private final FillSettledPublisher fills;

    public OrderEffectService(SpotOrderRepository spotOrders, SpotTradeRepository spotTrades,
                              SpotOrderFillLogRepository fillLogs, FillSettledPublisher fills) {
        this.spotOrders = spotOrders;
        this.spotTrades = spotTrades;
        this.fillLogs = fillLogs;
        this.fills = fills;
    }

    /** One order's contribution to one trade -- {@link #deriveFillContributions} splits
     *  a {@link TradeExecuted} into two of these (buyer's and seller's), and {@link
     *  #applyFillsForOrder} takes however many of them, across however many trades,
     *  landed for ONE order in a batch. */
    public record FillContribution(UUID tradeId, long fillQuantity, long fillQuoteQuantity,
                                   boolean fillsCompletely) {
    }

    /** Pairs a {@link FillContribution} with which order it belongs to, so the caller
     *  can group many of these by {@code orderId} the same way {@link BalanceMutation}s
     *  are grouped by {@code (userId, assetId)}. */
    public record OrderFillContribution(UUID orderId, FillContribution contribution) {
    }

    /** No database access -- pure derivation from the event, so the caller (batching
     *  many trades' worth of these before dispatching {@link #applyFillsForOrder}) never
     *  needs to re-derive taker/maker-to-buyer/seller mapping itself. */
    public List<OrderFillContribution> deriveFillContributions(TradeExecuted trade) {
        ResolvedTrade r = resolve(trade);
        return List.of(
                new OrderFillContribution(r.buyerOrderId(),
                        new FillContribution(r.tradeId(), trade.getQuantity(),
                                trade.getQuoteQuantity(), r.buyerFillsCompletely())),
                new OrderFillContribution(r.sellerOrderId(),
                        new FillContribution(r.tradeId(), trade.getQuantity(),
                                trade.getQuoteQuantity(), r.sellerFillsCompletely())));
    }

    /**
     * Applies every fill in {@code contributions} destined for ONE order as a single
     * aggregate write. Each one is checked against {@link SpotOrderFillLog} first --
     * whichever trades this order has NOT already counted (almost always all of them;
     * only a redelivery after a partial prior success would find some already there)
     * are summed, logged, and applied together. If none are new, this is a complete
     * no-op -- no log rows written, no update sent.
     */
    @Transactional
    public void applyFillsForOrder(UUID orderId, List<FillContribution> contributions) {
        List<UUID> tradeIds = contributions.stream().map(FillContribution::tradeId).toList();
        Set<UUID> alreadyApplied = new HashSet<>(fillLogs.findExistingTradeIds(orderId, tradeIds));
        List<FillContribution> newContributions = contributions.stream()
                .filter(c -> !alreadyApplied.contains(c.tradeId()))
                .toList();
        if (newContributions.isEmpty()) {
            return;
        }

        long totalQuantity = 0;
        long totalQuoteQuantity = 0;
        boolean fillsCompletely = false;
        List<SpotOrderFillLog> logs = new ArrayList<>(newContributions.size());
        for (FillContribution c : newContributions) {
            totalQuantity += c.fillQuantity();
            totalQuoteQuantity += c.fillQuoteQuantity();
            fillsCompletely = fillsCompletely || c.fillsCompletely();
            logs.add(new SpotOrderFillLog(orderId, c.tradeId()));
        }
        fillLogs.saveAll(logs);

        int rows = spotOrders.applyFillAtomically(orderId, totalQuantity, totalQuoteQuantity,
                fillsCompletely);
        if (rows == 0) {
            throw new IllegalStateException("aggregate fill rejected as an overfill (or order "
                    + orderId + " does not exist): totalQuantity=" + totalQuantity
                    + " totalQuoteQuantity=" + totalQuoteQuantity);
        }
    }

    /** One {@link TradeExecuted} plus the two fields the wire event doesn't carry --
     *  bundles what {@link #recordTrades} needs per trade, the same way {@link
     *  FillContribution} bundles what {@link #applyFillsForOrder} needs. */
    public record TradeToRecord(TradeExecuted trade, long sequence, Instant executedAt) {
    }

    /**
     * Records every trade in {@code trades} -- all for the same {@code symbol}, almost
     * always the whole symbol's share of one batch -- as new {@link SpotTrade} rows,
     * skipping whichever {@code sequence}s have already been seen, and returns the
     * wallet balance mutations every one of them implies. Touches neither order; {@link
     * #applyFillsForOrder} does that, separately, batched across however many trades
     * landed for that one order.
     *
     * <p>The existence check is one bulk query ({@link
     * SpotTradeRepository#findExistingSequences}) instead of one per trade, and the new
     * rows are written with one {@code saveAll} instead of one {@code save} per trade --
     * this is what actually makes the inserts collapse into a handful of JDBC batches
     * (see {@code application.yml}'s {@code hibernate.jdbc.batch_size} comment) rather
     * than one round trip per trade.
     *
     * @return the balance mutations these trades imply -- ALWAYS computed and returned
     *         for EVERY trade, even one already recorded on a redelivery. This used to
     *         be gated by the same existence check as the {@code SpotTrade} write, and
     *         that was a real bug: if a batch aborts later because some UNRELATED
     *         balance group fails, a trade's own already-committed row would make a
     *         redelivery see it as "already applied" and skip recomputing its
     *         mutations -- permanently losing them, even though they were never
     *         actually applied to a balance. Balance mutations are cheap to recompute
     *         and are already independently protected by {@code BalanceService}'s own
     *         idempotency key, so they no longer need a second gate that can fall out
     *         of sync with whether they were truly applied.
     */
    @Transactional
    public List<BalanceMutation> recordTrades(SpotSymbol symbol, List<TradeToRecord> trades) {
        List<Long> sequences = trades.stream().map(TradeToRecord::sequence).toList();
        Set<Long> alreadyRecorded = new HashSet<>(
                spotTrades.findExistingSequences(symbol.getId(), sequences));

        List<SpotTrade> newTrades = new ArrayList<>();
        List<BalanceMutation> mutations = new ArrayList<>(trades.size() * 6);
        for (TradeToRecord t : trades) {
            TradeExecuted trade = t.trade();
            ResolvedTrade r = resolve(trade);

            // A buyer pays their fee in the base asset received; a seller in the quote
            // asset received. bps can be negative (a maker rebate) -- Money.applyBps
            // carries that sign through, so the amounts below can be negative too.
            int buyerFeeBps = r.takerIsBuyer() ? trade.getTakerFeeBps() : trade.getMakerFeeBps();
            int sellerFeeBps = r.takerIsBuyer() ? trade.getMakerFeeBps() : trade.getTakerFeeBps();
            long buyerFee = Money.applyBps(trade.getQuantity(), buyerFeeBps);
            long sellerFee = Money.applyBps(trade.getQuoteQuantity(), sellerFeeBps);

            if (!alreadyRecorded.contains(t.sequence())) {
                SpotTrade spotTrade = new SpotTrade(r.tradeId(), symbol.getId(), trade.getPrice(),
                        trade.getQuantity(), r.buyerOrderId(), r.sellerOrderId(), r.buyerUserId(),
                        r.sellerUserId(), !r.takerIsBuyer(), t.sequence(), t.executedAt());
                // Signed. A maker rebate is a negative fee and must stay negative: nothing
                // else on the trade row carries the rate, so stripping the sign made a
                // rebate indistinguishable from a charge to every reader of it. The balance
                // was always credited correctly; only the record disagreed. The row's
                // CHECK allows a negative fee for the maker side only, so a negative TAKER
                // fee is still rejected.
                spotTrade.chargeFees(buyerFee, symbol.getBaseAssetId(),
                        sellerFee, symbol.getQuoteAssetId());
                newTrades.add(spotTrade);
            }

            String tradeIdStr = r.tradeId().toString();
            mutations.add(new BalanceMutation(BalanceMutation.Kind.SETTLE_LOCKED, r.buyerUserId(),
                    symbol.getQuoteAssetId(), trade.getQuoteQuantity(), ReferenceType.TRADE,
                    tradeIdStr, "trade:" + tradeIdStr + ":buyer:settle-lock"));
            mutations.add(new BalanceMutation(BalanceMutation.Kind.CREDIT, r.buyerUserId(),
                    symbol.getBaseAssetId(), trade.getQuantity(), ReferenceType.TRADE,
                    tradeIdStr, "trade:" + tradeIdStr + ":buyer:credit"));
            addFeeMutation(mutations, r.buyerUserId(), symbol.getBaseAssetId(), buyerFee,
                    tradeIdStr, "buyer");
            addImprovementUnlock(mutations, symbol, trade, r, tradeIdStr);
            mutations.add(new BalanceMutation(BalanceMutation.Kind.SETTLE_LOCKED, r.sellerUserId(),
                    symbol.getBaseAssetId(), trade.getQuantity(), ReferenceType.TRADE,
                    tradeIdStr, "trade:" + tradeIdStr + ":seller:settle-lock"));
            mutations.add(new BalanceMutation(BalanceMutation.Kind.CREDIT, r.sellerUserId(),
                    symbol.getQuoteAssetId(), trade.getQuoteQuantity(), ReferenceType.TRADE,
                    tradeIdStr, "trade:" + tradeIdStr + ":seller:credit"));
            addFeeMutation(mutations, r.sellerUserId(), symbol.getQuoteAssetId(), sellerFee,
                    tradeIdStr, "seller");
        }
        spotTrades.saveAll(newTrades);
        // Only what was actually inserted. A redelivered batch re-derives every mutation but
        // inserts nothing, and announcing then would report a fill that happened once as
        // having happened twice.
        fills.publishAfterCommit(symbol.getSymbol(), newTrades);
        return mutations;
    }

    /**
     * A buying taker locked its own limit price per unit and paid the maker's, which is at
     * most that. The difference belongs to the user now, not when the order closes: a cancel
     * releases only what the unfilled part holds, and a FILLED order is never looked at again.
     * A buying maker fills at exactly its own price, and a quote-sized order locks a budget,
     * so neither has anything to release here.
     */
    private static void addImprovementUnlock(List<BalanceMutation> mutations, SpotSymbol symbol,
                                             TradeExecuted trade, ResolvedTrade r, String tradeIdStr) {
        if (!r.takerIsBuyer() || trade.getTakerLimitPrice() <= 0) {
            return;
        }
        long improvement = symbol.notionalOf(trade.getTakerLimitPrice(), trade.getQuantity())
                - trade.getQuoteQuantity();
        if (improvement > 0) {
            mutations.add(new BalanceMutation(BalanceMutation.Kind.UNLOCK, r.buyerUserId(),
                    symbol.getQuoteAssetId(), improvement, ReferenceType.ORDER,
                    r.buyerOrderId().toString(), "trade:" + tradeIdStr + ":buyer:improvement-unlock"));
        }
    }

    /** Zero fee writes nothing -- a $0 ledger entry records nothing a missing one
     *  doesn't already say. A negative fee is a rebate, credited rather than debited,
     *  and ledgered under its own type so it stays distinguishable from ordinary trade
     *  proceeds -- see BalanceService#rebate. */
    private void addFeeMutation(List<BalanceMutation> mutations, Long userId, Long assetId,
                                long signedFee, String tradeIdStr, String side) {
        if (signedFee == 0) {
            return;
        }
        String idempotencyKey = "trade:" + tradeIdStr + ":" + side + ":fee";
        mutations.add(signedFee > 0
                ? new BalanceMutation(BalanceMutation.Kind.DEBIT, userId, assetId, signedFee,
                        ReferenceType.TRADE, tradeIdStr, idempotencyKey)
                : new BalanceMutation(BalanceMutation.Kind.REBATE, userId, assetId, -signedFee,
                        ReferenceType.TRADE, tradeIdStr, idempotencyKey));
    }

    /**
     * The engine's own late-stage rejection (self-trade prevention, a stop price
     * already on the wrong side of a market that moved since placement, a LIMIT_MAKER
     * crossing the spread) -- {@code OrderService}'s synchronous validation at
     * placement already caught everything it can; this is the rest. Always follows
     * zero prior fills: a rejection never follows a partial fill, that path is a
     * cancel. Naturally idempotent on redelivery -- re-setting the same terminal
     * status and re-unlocking under the same idempotency key both no-op harmlessly, so
     * this needs no {@code (symbolId, sequence)}-style guard the way a trade does.
     *
     * @return the unlock mutation
     * @throws IllegalStateException no row for the order: the batch is redelivered until
     *         there is one -- see docs/reference/delivery-guarantees.md, "Known gaps"
     */
    @Transactional
    public List<BalanceMutation> applyReject(SpotSymbol symbol, OrderRejected rejected) {
        UUID orderId = Ulid.toUuid(rejected.getOrderId().toByteArray());
        SpotOrder order = requireOrder(orderId);
        order.setStatus(OrderStatus.REJECTED);
        order.setRejectReason(rejected.getReason().name());
        spotOrders.save(order);
        return remainingLockMutation(symbol, order, "reject");
    }

    /**
     * May follow zero or more prior fills of the same order within this same batch (a
     * full cancel, an IOC/FOK remainder, or a user-cancelled partially-filled resting
     * order) -- correct only because the caller applies every fill for this batch (via
     * {@link #applyFillsForOrder}) before processing any reject/cancel event.
     *
     * @return the unlock mutation
     * @throws IllegalStateException no row for the order: the batch is redelivered until
     *         there is one -- see docs/reference/delivery-guarantees.md, "Known gaps"
     */
    @Transactional
    public List<BalanceMutation> applyCancel(SpotSymbol symbol, OrderCanceled canceled) {
        UUID orderId = Ulid.toUuid(canceled.getOrderId().toByteArray());
        SpotOrder order = requireOrder(orderId);
        order.setStatus(OrderStatus.CANCELED);
        spotOrders.save(order);
        return remainingLockMutation(symbol, order, "cancel");
    }

    /**
     * The order's row takes its new terms, and whatever the new terms no longer need is
     * released: what the old terms held, plus what the API locked on top, minus what the new
     * terms hold. All three come from the event, so this is right wherever in the batch the
     * order's fills fall. Idempotent: the same values written twice, one unlock key per command.
     *
     * @return the unlock mutation, or an empty list if nothing is surplus
     */
    @Transactional
    public List<BalanceMutation> applyAmend(SpotSymbol symbol, OrderAmended amended, UUID commandId) {
        SpotOrder order = requireOrder(Ulid.toUuid(amended.getOrderId().toByteArray()));
        long held = OrderLock.amount(symbol, order,
                amended.hasOldPrice() ? amended.getOldPrice() : null, amended.getOldRemainingQuantity());
        long needed = OrderLock.amount(symbol, order,
                amended.hasNewPrice() ? amended.getNewPrice() : null, amended.getNewRemainingQuantity());
        order.applyAmend(amended.hasNewPrice() ? amended.getNewPrice() : null,
                amended.hasNewStopPrice() ? amended.getNewStopPrice() : null,
                amended.getNewQuantity());
        spotOrders.save(order);

        long surplus = held + amended.getAdditionalLocked() - needed;
        if (surplus < 0) {
            // OrderLock.additionalFor exists so this cannot happen. Nothing to take back here.
            log.error("order {} is under-locked by {} after amend {}", order.getId(), -surplus, commandId);
        }
        return amendUnlock(symbol, order, surplus, commandId);
    }

    /** The order is as it was; only the API's extra lock has to go back. */
    @Transactional
    public List<BalanceMutation> applyAmendReject(SpotSymbol symbol, OrderAmendRejected rejected,
                                                  UUID commandId) {
        SpotOrder order = requireOrder(Ulid.toUuid(rejected.getOrderId().toByteArray()));
        order.setAmendPending(false);
        spotOrders.save(order);
        return amendUnlock(symbol, order, rejected.getAdditionalLocked(), commandId);
    }

    private static List<BalanceMutation> amendUnlock(SpotSymbol symbol, SpotOrder order, long amount,
                                                     UUID commandId) {
        if (amount <= 0) {
            return List.of();
        }
        String orderIdStr = order.getId().toString();
        return List.of(new BalanceMutation(BalanceMutation.Kind.UNLOCK, order.getUserId(),
                OrderLock.assetId(symbol, order), amount, ReferenceType.ORDER, orderIdStr,
                "order:" + orderIdStr + ":amend:" + commandId + "-unlock"));
    }

    /**
     * A maker's own resting order always fills at exactly its own limit price, so
     * {@code price * remainingQuantity()} is exact here, never approximated -- see
     * SpotOrder#applyFill's own javadoc for why the same is not true of a taker.
     */
    private List<BalanceMutation> remainingLockMutation(SpotSymbol symbol, SpotOrder order,
                                                         String reason) {
        Long lockAssetId = OrderLock.assetId(symbol, order);
        long remainingLock = OrderLock.amount(symbol, order);
        if (remainingLock <= 0) {
            return List.of();
        }
        String orderIdStr = order.getId().toString();
        return List.of(new BalanceMutation(BalanceMutation.Kind.UNLOCK, order.getUserId(),
                lockAssetId, remainingLock, ReferenceType.ORDER, orderIdStr,
                "order:" + orderIdStr + ":" + reason + "-unlock"));
    }

    private SpotOrder requireOrder(UUID orderId) {
        return spotOrders.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("no SpotOrder for id " + orderId));
    }

    private record ResolvedTrade(UUID tradeId, UUID buyerOrderId, UUID sellerOrderId,
                                 Long buyerUserId, Long sellerUserId, boolean takerIsBuyer,
                                 boolean buyerFillsCompletely, boolean sellerFillsCompletely) {
    }

    private static ResolvedTrade resolve(TradeExecuted trade) {
        boolean takerIsBuyer =
                trade.getTakerSide() == com.exchange.contracts.spot.v1.Side.SIDE_BUY;
        UUID buyerOrderId = Ulid.toUuid(
                (takerIsBuyer ? trade.getTakerOrderId() : trade.getMakerOrderId()).toByteArray());
        UUID sellerOrderId = Ulid.toUuid(
                (takerIsBuyer ? trade.getMakerOrderId() : trade.getTakerOrderId()).toByteArray());
        Long buyerUserId = takerIsBuyer ? trade.getTakerUserId() : trade.getMakerUserId();
        Long sellerUserId = takerIsBuyer ? trade.getMakerUserId() : trade.getTakerUserId();
        com.exchange.contracts.spot.v1.OrderStatus buyerProtoStatus =
                takerIsBuyer ? trade.getTakerStatus() : trade.getMakerStatus();
        com.exchange.contracts.spot.v1.OrderStatus sellerProtoStatus =
                takerIsBuyer ? trade.getMakerStatus() : trade.getTakerStatus();
        UUID tradeId = Ulid.toUuid(trade.getTradeId().toByteArray());
        return new ResolvedTrade(tradeId, buyerOrderId, sellerOrderId, buyerUserId, sellerUserId,
                takerIsBuyer,
                buyerProtoStatus == com.exchange.contracts.spot.v1.OrderStatus.ORDER_STATUS_FILLED,
                sellerProtoStatus == com.exchange.contracts.spot.v1.OrderStatus.ORDER_STATUS_FILLED);
    }
}
