package com.exchange.spot.settlement.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.common.id.Ulid;
import com.exchange.contracts.spot.v1.TradeExecuted;
import com.exchange.spot.order.OrderStatus;
import com.exchange.spot.order.OrderType;
import com.exchange.spot.order.Side;
import com.exchange.spot.order.SpotOrder;
import com.exchange.spot.order.SpotOrderRepository;
import com.exchange.spot.order.TimeInForce;
import com.exchange.spot.orderfilllog.SpotOrderFillLog;
import com.exchange.spot.orderfilllog.SpotOrderFillLogRepository;
import com.exchange.spot.settlement.trade.OrderEffectService.FillContribution;
import com.exchange.spot.settlement.trade.OrderEffectService.OrderFillContribution;
import com.exchange.spot.settlement.trade.OrderEffectService.TradeToRecord;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.trade.SpotTrade;
import com.exchange.spot.trade.SpotTradeRepository;
import com.google.protobuf.ByteString;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link SpotOrderRepository}/{@link SpotTradeRepository}/{@link
 * SpotOrderFillLogRepository} are mocked; {@link SpotSymbol} is mocked too (like {@code
 * SpotExchangeOrderLimits} in {@code OrderServiceTest}) since its id has no public
 * setter outside JPA. What's under test is {@link OrderEffectService}'s own
 * orchestration -- which mutations a trade/reject/cancel implies, that a redelivered
 * trade is a true no-op on its order side but NOT on its balance-mutation side, and
 * that an order's fill log correctly separates already-counted trades from new ones
 * before aggregating -- not {@code BalanceService} or JPA itself.
 */
@ExtendWith(MockitoExtension.class)
class OrderEffectServiceTest {

    private static final Long SYMBOL_ID = 1L;
    private static final Long BASE_ASSET_ID = 10L;
    private static final Long QUOTE_ASSET_ID = 20L;
    private static final Long BUYER_ID = 100L;
    private static final Long SELLER_ID = 200L;
    private static final long PRICE = 6_325_000_000_000L;   // 63,250.00000000
    private static final long QUANTITY = 100_000_000L;      // 1.0 -- so quoteQuantity == PRICE
    private static final long SEQUENCE = 42L;

    @Mock
    private SpotOrderRepository spotOrders;
    @Mock
    private SpotTradeRepository spotTrades;
    @Mock
    private SpotOrderFillLogRepository fillLogs;

    @Mock
    private FillSettledPublisher fills;

    private SpotSymbol symbol;
    private OrderEffectService service;

    @BeforeEach
    void setUp() {
        service = new OrderEffectService(spotOrders, spotTrades, fillLogs, fills);
        symbol = mock(SpotSymbol.class);
        lenient().when(symbol.getId()).thenReturn(SYMBOL_ID);
        lenient().when(symbol.getBaseAssetId()).thenReturn(BASE_ASSET_ID);
        lenient().when(symbol.getQuoteAssetId()).thenReturn(QUOTE_ASSET_ID);
        lenient().when(symbol.getSymbol()).thenReturn("OEITUSDT");
        lenient().when(spotOrders.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void recordTrades_insertsSpotTradeAndReturnsTheSixExpectedMutations() {
        UUID buyerOrderId = UUID.randomUUID();
        UUID sellerOrderId = UUID.randomUUID();
        UUID tradeId = UUID.randomUUID();
        TradeExecuted trade = tradeExecuted(tradeId, buyerOrderId, sellerOrderId,
                com.exchange.contracts.spot.v1.Side.SIDE_BUY, 7, -2); // taker buys; maker rebate
        when(spotTrades.findExistingSequences(SYMBOL_ID, List.of(SEQUENCE))).thenReturn(List.of());

        List<BalanceMutation> mutations = service.recordTrades(symbol,
                List.of(new TradeToRecord(trade, SEQUENCE, Instant.now())));

        ArgumentCaptor<List<SpotTrade>> tradeCaptor = ArgumentCaptor.forClass(List.class);
        verify(spotTrades).saveAll(tradeCaptor.capture());
        assertThat(tradeCaptor.getValue()).hasSize(1);
        SpotTrade saved = tradeCaptor.getValue().get(0);
        assertThat(saved.getId()).isEqualTo(tradeId);
        assertThat(saved.getBuyerFeeAssetId()).isEqualTo(BASE_ASSET_ID);
        assertThat(saved.getSellerFeeAssetId()).isEqualTo(QUOTE_ASSET_ID);

        // The taker bought, so the SELLER rested and takes the maker rate -- which is -2 bps
        // above, a rebate. The sign has to survive onto the row: nothing else on it carries
        // the rate, so a magnitude alone makes a rebate and a charge indistinguishable, and
        // every reader of spot_trades then reports a payment the user never made. This test
        // already used a negative maker rate and only checked the asset ids, which is exactly
        // how that went unnoticed.
        assertThat(saved.getBuyerFee()).as("taker pays").isPositive();
        assertThat(saved.getSellerFee()).as("maker is paid").isNegative();

        String t = tradeId.toString();
        assertThat(mutations).containsExactlyInAnyOrder(
                new BalanceMutation(BalanceMutation.Kind.SETTLE_LOCKED, BUYER_ID, QUOTE_ASSET_ID,
                        PRICE, com.exchange.wallet.ledger.ReferenceType.TRADE, t,
                        "trade:" + t + ":buyer:settle-lock"),
                new BalanceMutation(BalanceMutation.Kind.CREDIT, BUYER_ID, BASE_ASSET_ID,
                        QUANTITY, com.exchange.wallet.ledger.ReferenceType.TRADE, t,
                        "trade:" + t + ":buyer:credit"),
                new BalanceMutation(BalanceMutation.Kind.DEBIT, BUYER_ID, BASE_ASSET_ID,
                        70_000L, com.exchange.wallet.ledger.ReferenceType.TRADE, t, // 7bps of 1.0 BTC
                        "trade:" + t + ":buyer:fee"),
                new BalanceMutation(BalanceMutation.Kind.SETTLE_LOCKED, SELLER_ID, BASE_ASSET_ID,
                        QUANTITY, com.exchange.wallet.ledger.ReferenceType.TRADE, t,
                        "trade:" + t + ":seller:settle-lock"),
                new BalanceMutation(BalanceMutation.Kind.CREDIT, SELLER_ID, QUOTE_ASSET_ID,
                        PRICE, com.exchange.wallet.ledger.ReferenceType.TRADE, t,
                        "trade:" + t + ":seller:credit"),
                new BalanceMutation(BalanceMutation.Kind.REBATE, SELLER_ID, QUOTE_ASSET_ID,
                        1_265_000_000L, com.exchange.wallet.ledger.ReferenceType.TRADE, t, // 2bps rebate
                        "trade:" + t + ":seller:fee"));
    }

    @Test
    void recordTrades_alreadyApplied_skipsTheOrderSideButStillReturnsTheMutations() {
        // Regression test for a real bug: gating the returned mutations on this same
        // check (as an earlier version of this method did) meant that if a batch
        // aborted for a reason unrelated to this trade -- some OTHER balance group
        // failing, after this trade's SpotTrade row already committed -- a redelivery
        // would see it "already applied" and silently never re-emit these mutations at
        // all, even though they were never actually applied to a balance. The insert
        // stays gated (never re-attempted, that part was always correct); the
        // mutations must not be.
        UUID buyerOrderId = UUID.randomUUID();
        UUID sellerOrderId = UUID.randomUUID();
        when(spotTrades.findExistingSequences(SYMBOL_ID, List.of(SEQUENCE)))
                .thenReturn(List.of(SEQUENCE));

        List<BalanceMutation> mutations = service.recordTrades(symbol, List.of(new TradeToRecord(
                tradeExecuted(UUID.randomUUID(), buyerOrderId, sellerOrderId,
                        com.exchange.contracts.spot.v1.Side.SIDE_BUY, 7, 7),
                SEQUENCE, Instant.now())));

        assertThat(mutations).hasSize(6); // settle-lock + credit + fee, per side
        verify(spotTrades, never()).save(any());
        verify(spotTrades).saveAll(List.of());
    }

    @Test
    void deriveFillContributions_splitsATradeIntoBuyerAndSellerContributions() {
        UUID buyerOrderId = UUID.randomUUID();
        UUID sellerOrderId = UUID.randomUUID();
        UUID tradeId = UUID.randomUUID();
        // Taker sells: taker is the seller, maker is the buyer -- maker (buyer) status
        // reported PARTIALLY_FILLED, taker (seller) status reported FILLED.
        TradeExecuted trade = TradeExecuted.newBuilder()
                .setTradeId(bytes(tradeId))
                .setTakerOrderId(bytes(sellerOrderId))
                .setMakerOrderId(bytes(buyerOrderId))
                .setTakerUserId(SELLER_ID)
                .setMakerUserId(BUYER_ID)
                .setTakerSide(com.exchange.contracts.spot.v1.Side.SIDE_SELL)
                .setPrice(PRICE)
                .setQuantity(QUANTITY)
                .setQuoteQuantity(PRICE)
                .setTakerFeeBps(7)
                .setMakerFeeBps(7)
                .setTakerStatus(com.exchange.contracts.spot.v1.OrderStatus.ORDER_STATUS_FILLED)
                .setMakerStatus(com.exchange.contracts.spot.v1.OrderStatus.ORDER_STATUS_PARTIALLY_FILLED)
                .build();

        List<OrderFillContribution> contributions = service.deriveFillContributions(trade);

        assertThat(contributions).containsExactlyInAnyOrder(
                new OrderFillContribution(buyerOrderId,
                        new FillContribution(tradeId, QUANTITY, PRICE, false)),
                new OrderFillContribution(sellerOrderId,
                        new FillContribution(tradeId, QUANTITY, PRICE, true)));
    }

    @Test
    void applyFillsForOrder_sumsAllNewContributionsIntoOneAggregateWrite() {
        UUID orderId = UUID.randomUUID();
        UUID trade1 = UUID.randomUUID();
        UUID trade2 = UUID.randomUUID();
        List<FillContribution> contributions = List.of(
                new FillContribution(trade1, 30_000_000L, 190_000_000L, false),
                new FillContribution(trade2, 70_000_000L, 440_000_000L, true));
        when(fillLogs.findExistingTradeIds(orderId, List.of(trade1, trade2)))
                .thenReturn(List.of());
        when(spotOrders.applyFillAtomically(orderId, 100_000_000L, 630_000_000L, true))
                .thenReturn(1);

        service.applyFillsForOrder(orderId, contributions);

        verify(spotOrders).applyFillAtomically(orderId, 100_000_000L, 630_000_000L, true);
        ArgumentCaptor<List<SpotOrderFillLog>> logsCaptor = ArgumentCaptor.forClass(List.class);
        verify(fillLogs).saveAll(logsCaptor.capture());
        assertThat(logsCaptor.getValue()).hasSize(2);
    }

    @Test
    void applyFillsForOrder_excludesAlreadyLoggedTradesFromTheAggregate() {
        UUID orderId = UUID.randomUUID();
        UUID alreadyApplied = UUID.randomUUID();
        UUID stillNew = UUID.randomUUID();
        List<FillContribution> contributions = List.of(
                new FillContribution(alreadyApplied, 30_000_000L, 190_000_000L, false),
                new FillContribution(stillNew, 70_000_000L, 440_000_000L, true));
        when(fillLogs.findExistingTradeIds(orderId, List.of(alreadyApplied, stillNew)))
                .thenReturn(List.of(alreadyApplied));
        when(spotOrders.applyFillAtomically(orderId, 70_000_000L, 440_000_000L, true))
                .thenReturn(1);

        service.applyFillsForOrder(orderId, contributions);

        verify(spotOrders).applyFillAtomically(orderId, 70_000_000L, 440_000_000L, true);
        ArgumentCaptor<List<SpotOrderFillLog>> logsCaptor = ArgumentCaptor.forClass(List.class);
        verify(fillLogs).saveAll(logsCaptor.capture());
        assertThat(logsCaptor.getValue()).hasSize(1);
    }

    @Test
    void applyFillsForOrder_whenEveryContributionIsAlreadyLogged_isACompleteNoOp() {
        UUID orderId = UUID.randomUUID();
        UUID tradeId = UUID.randomUUID();
        List<FillContribution> contributions =
                List.of(new FillContribution(tradeId, QUANTITY, PRICE, true));
        when(fillLogs.findExistingTradeIds(orderId, List.of(tradeId)))
                .thenReturn(List.of(tradeId));

        service.applyFillsForOrder(orderId, contributions);

        verify(fillLogs, never()).saveAll(any());
        verify(spotOrders, never()).applyFillAtomically(any(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void applyFillsForOrder_whenTheAggregateIsRejectedAsAnOverfill_throws() {
        UUID orderId = UUID.randomUUID();
        UUID tradeId = UUID.randomUUID();
        List<FillContribution> contributions =
                List.of(new FillContribution(tradeId, QUANTITY, PRICE, true));
        when(fillLogs.findExistingTradeIds(orderId, List.of(tradeId))).thenReturn(List.of());
        when(spotOrders.applyFillAtomically(orderId, QUANTITY, PRICE, true)).thenReturn(0);

        assertThatThrownBy(() -> service.applyFillsForOrder(orderId, contributions))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overfill");
    }

    @Test
    void applyReject_forAPriceBasedBuy_unlocksNotionalInQuoteAndSetsRejected() {
        SpotOrder order = limitOrder(Side.BUY, QUANTITY);
        when(spotOrders.findById(order.getId())).thenReturn(Optional.of(order));
        when(symbol.notionalOf(PRICE, QUANTITY)).thenReturn(PRICE); // 1.0 qty -- notional == price

        List<BalanceMutation> mutations = service.applyReject(symbol, rejected(order.getId()));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getRejectReason()).isNotNull();
        assertThat(mutations).hasSize(1);
        BalanceMutation unlock = mutations.get(0);
        assertThat(unlock.kind()).isEqualTo(BalanceMutation.Kind.UNLOCK);
        assertThat(unlock.assetId()).isEqualTo(QUOTE_ASSET_ID);
        assertThat(unlock.amount()).isEqualTo(PRICE);
    }

    @Test
    void applyCancel_forASellOrderPartiallyFilled_unlocksOnlyTheRemainingBaseQuantity() {
        SpotOrder order = limitOrder(Side.SELL, QUANTITY);
        order.setFilledQuantity(30_000_000L); // 0.3 of 1.0 filled
        when(spotOrders.findById(order.getId())).thenReturn(Optional.of(order));

        List<BalanceMutation> mutations = service.applyCancel(symbol, canceled(order.getId()));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(mutations).hasSize(1);
        BalanceMutation unlock = mutations.get(0);
        assertThat(unlock.assetId()).isEqualTo(BASE_ASSET_ID);
        assertThat(unlock.amount()).isEqualTo(70_000_000L); // 0.7 remaining
    }

    @Test
    void applyCancel_forAQuoteOrderQtyBuy_unlocksTheRemainingQuoteBudget() {
        SpotOrder order = marketBuyByQuote(1_000_000_000L); // $10 budget
        order.setCumulativeQuoteQuantity(400_000_000L); // $4 already spent
        when(spotOrders.findById(order.getId())).thenReturn(Optional.of(order));

        List<BalanceMutation> mutations = service.applyCancel(symbol, canceled(order.getId()));

        assertThat(mutations).hasSize(1);
        BalanceMutation unlock = mutations.get(0);
        assertThat(unlock.assetId()).isEqualTo(QUOTE_ASSET_ID);
        assertThat(unlock.amount()).isEqualTo(600_000_000L); // $6 remaining
    }

    // ------------------------------------------------------------------ amend

    private static final UUID AMEND_COMMAND = UUID.randomUUID();

    private static com.exchange.contracts.spot.v1.OrderAmended.Builder amended(SpotOrder order) {
        return com.exchange.contracts.spot.v1.OrderAmended.newBuilder()
                .setOrderId(com.google.protobuf.ByteString.copyFrom(
                        com.exchange.common.id.Ulid.toBytes(order.getId())));
    }

    private void realNotional() {
        when(symbol.notionalOf(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> com.exchange.common.money.Money.multiply(
                        inv.getArgument(0, Long.class), inv.getArgument(1, Long.class)));
    }

    /** 1 @ P held P. The API locked P more for 2 @ P; half of the order filled on the way, so
     *  the engine's amend left 1.5 @ P: held 0.5P + P extra, needs 1.5P, nothing surplus. */
    @Test
    void applyAmend_afterAFillInFlight_releasesOnlyWhatTheNewTermsDoNotNeed() {
        realNotional();
        SpotOrder order = limitOrder(Side.BUY, QUANTITY);
        order.setAmendPending(true);
        when(spotOrders.findById(order.getId())).thenReturn(Optional.of(order));

        List<BalanceMutation> mutations = service.applyAmend(symbol, amended(order)
                .setOldPrice(PRICE).setNewPrice(PRICE)
                .setOldRemainingQuantity(QUANTITY / 2).setNewRemainingQuantity(3 * QUANTITY / 2)
                .setNewQuantity(2 * QUANTITY).setAdditionalLocked(PRICE).build(), AMEND_COMMAND);

        assertThat(mutations).isEmpty();
        assertThat(order.getQuantity()).isEqualTo(2 * QUANTITY);
        assertThat(order.isAmendPending()).isFalse();
    }

    @Test
    void applyAmend_toALowerBuyPrice_releasesTheDifferenceOnWhatIsLeft() {
        realNotional();
        SpotOrder order = limitOrder(Side.BUY, QUANTITY);
        when(spotOrders.findById(order.getId())).thenReturn(Optional.of(order));

        List<BalanceMutation> mutations = service.applyAmend(symbol, amended(order)
                .setOldPrice(PRICE).setNewPrice(PRICE / 2)
                .setOldRemainingQuantity(QUANTITY).setNewRemainingQuantity(QUANTITY)
                .setNewQuantity(QUANTITY).build(), AMEND_COMMAND);

        assertThat(mutations).hasSize(1);
        BalanceMutation unlock = mutations.get(0);
        assertThat(unlock.kind()).isEqualTo(BalanceMutation.Kind.UNLOCK);
        assertThat(unlock.assetId()).isEqualTo(QUOTE_ASSET_ID);
        assertThat(unlock.amount()).isEqualTo(PRICE / 2);
        assertThat(unlock.idempotencyKey()).contains(AMEND_COMMAND.toString());
        assertThat(order.getPrice()).isEqualTo(PRICE / 2);
    }

    @Test
    void applyAmend_ofASellToASmallerSize_releasesBaseAndIgnoresThePrice() {
        SpotOrder order = limitOrder(Side.SELL, QUANTITY);
        when(spotOrders.findById(order.getId())).thenReturn(Optional.of(order));

        List<BalanceMutation> mutations = service.applyAmend(symbol, amended(order)
                .setOldPrice(PRICE).setNewPrice(2 * PRICE)
                .setOldRemainingQuantity(QUANTITY).setNewRemainingQuantity(QUANTITY / 4)
                .setNewQuantity(QUANTITY / 4).build(), AMEND_COMMAND);

        assertThat(mutations.get(0).assetId()).isEqualTo(BASE_ASSET_ID);
        assertThat(mutations.get(0).amount()).isEqualTo(3 * QUANTITY / 4);
    }

    @Test
    void applyAmend_ofAParkedStop_movesTheStopPriceAndReleasesNothing() {
        SpotOrder order = new SpotOrder(UUID.randomUUID(), SELLER_ID, SYMBOL_ID, Side.SELL,
                OrderType.STOP_LOSS, TimeInForce.GTC, null, PRICE, QUANTITY, null, 7, 7, null, null);
        when(spotOrders.findById(order.getId())).thenReturn(Optional.of(order));

        List<BalanceMutation> mutations = service.applyAmend(symbol, amended(order)
                .setOldStopPrice(PRICE).setNewStopPrice(PRICE / 2).setPendingTrigger(true)
                .setOldRemainingQuantity(QUANTITY).setNewRemainingQuantity(QUANTITY)
                .setNewQuantity(QUANTITY).build(), AMEND_COMMAND);

        assertThat(mutations).isEmpty();
        assertThat(order.getStopPrice()).isEqualTo(PRICE / 2);
    }

    @Test
    void applyAmendReject_returnsTheExtraLockAndLeavesTheOrderAsItWas() {
        SpotOrder order = limitOrder(Side.BUY, QUANTITY);
        order.setAmendPending(true);
        when(spotOrders.findById(order.getId())).thenReturn(Optional.of(order));

        List<BalanceMutation> mutations = service.applyAmendReject(symbol,
                com.exchange.contracts.spot.v1.OrderAmendRejected.newBuilder()
                        .setOrderId(com.google.protobuf.ByteString.copyFrom(
                                com.exchange.common.id.Ulid.toBytes(order.getId())))
                        .setAdditionalLocked(12_345L).build(), AMEND_COMMAND);

        assertThat(mutations.get(0).amount()).isEqualTo(12_345L);
        assertThat(mutations.get(0).assetId()).isEqualTo(QUOTE_ASSET_ID);
        assertThat(order.isAmendPending()).isFalse();
        assertThat(order.getPrice()).isEqualTo(PRICE);
    }

    private static SpotOrder limitOrder(Side side, long quantity) {
        return new SpotOrder(UUID.randomUUID(), side == Side.BUY ? BUYER_ID : SELLER_ID, SYMBOL_ID,
                side, OrderType.LIMIT, TimeInForce.GTC, PRICE, null, quantity, null, 7, 7, null, null);
    }

    /**
     * A redelivered trade is announced to nobody.
     *
     * <p>{@code recordTrades} re-derives every balance mutation on a redelivery -- that is what
     * makes settlement idempotent -- but inserts no row. Announcing the re-derived trades
     * rather than the inserted ones would tell a client a fill they already saw had happened
     * again, and there is nothing downstream that could tell the difference.
     *
     * <p>Found by a negative control: breaking this passed every other test in the suite.
     */
    @Test
    void recordTrades_announcesOnlyTheTradesActuallyInserted() {
        UUID tradeId = UUID.randomUUID();
        TradeExecuted trade = tradeExecuted(tradeId, UUID.randomUUID(), UUID.randomUUID(),
                com.exchange.contracts.spot.v1.Side.SIDE_BUY, 7, -2);
        // Already recorded: the guard that makes a redelivery a no-op for the trade table.
        when(spotTrades.findExistingSequences(SYMBOL_ID, List.of(SEQUENCE)))
                .thenReturn(List.of(SEQUENCE));

        List<BalanceMutation> mutations = service.recordTrades(symbol,
                List.of(new TradeToRecord(trade, SEQUENCE, Instant.now())));

        // The mutations are still derived -- idempotency lives in BalanceService, not here.
        assertThat(mutations).isNotEmpty();
        ArgumentCaptor<List<SpotTrade>> announced = ArgumentCaptor.forClass(List.class);
        verify(fills).publishAfterCommit(anyString(), announced.capture());
        assertThat(announced.getValue()).as("nothing was inserted, so nothing to announce")
                .isEmpty();
    }

    /** The same rebate, on the other side. Hard-coding which column may go negative is the
     *  mistake spot_trades' fee constraint exists to prevent, and this is the test that would catch it. */
    @Test
    void recordTrades_storesTheRebateOnWhicheverSideRested() {
        UUID buyerOrderId = UUID.randomUUID();
        UUID sellerOrderId = UUID.randomUUID();
        UUID tradeId = UUID.randomUUID();
        // Taker SELLS, so the BUYER rested and takes the maker rebate.
        TradeExecuted trade = tradeExecuted(tradeId, buyerOrderId, sellerOrderId,
                com.exchange.contracts.spot.v1.Side.SIDE_SELL, 7, -2);
        when(spotTrades.findExistingSequences(SYMBOL_ID, List.of(SEQUENCE))).thenReturn(List.of());

        service.recordTrades(symbol, List.of(new TradeToRecord(trade, SEQUENCE, Instant.now())));

        ArgumentCaptor<List<SpotTrade>> tradeCaptor = ArgumentCaptor.forClass(List.class);
        verify(spotTrades).saveAll(tradeCaptor.capture());
        SpotTrade saved = tradeCaptor.getValue().get(0);
        assertThat(saved.getBuyerFee()).as("maker is paid").isNegative();
        assertThat(saved.getSellerFee()).as("taker pays").isPositive();
    }

    private static SpotOrder marketBuyByQuote(long quoteOrderQty) {
        return new SpotOrder(UUID.randomUUID(), BUYER_ID, SYMBOL_ID, Side.BUY, OrderType.MARKET,
                TimeInForce.IOC, null, null, 0L, quoteOrderQty, 7, 7, null, null);
    }

    /** A buyer who bid PRICE + 10% and was filled at PRICE locked 10% more than it paid. */
    @Test
    void recordTrades_releasesABuyingTakersPriceImprovementOnEveryFill() {
        realNotional();
        UUID buyerOrderId = UUID.randomUUID();
        TradeExecuted trade = tradeExecuted(UUID.randomUUID(), buyerOrderId, UUID.randomUUID(),
                com.exchange.contracts.spot.v1.Side.SIDE_BUY, 7, 7).toBuilder()
                .setTakerLimitPrice(PRICE + PRICE / 10).build();
        when(spotTrades.findExistingSequences(SYMBOL_ID, List.of(SEQUENCE))).thenReturn(List.of());

        List<BalanceMutation> mutations = service.recordTrades(symbol,
                List.of(new TradeToRecord(trade, SEQUENCE, Instant.now())));

        List<BalanceMutation> unlocks = mutations.stream()
                .filter(m -> m.kind() == BalanceMutation.Kind.UNLOCK).toList();
        assertThat(unlocks).hasSize(1);
        assertThat(unlocks.get(0).userId()).isEqualTo(BUYER_ID);
        assertThat(unlocks.get(0).assetId()).isEqualTo(QUOTE_ASSET_ID);
        assertThat(unlocks.get(0).amount()).isEqualTo(PRICE / 10);
        assertThat(unlocks.get(0).referenceId()).isEqualTo(buyerOrderId.toString());
        assertThat(unlocks.get(0).idempotencyKey()).contains("improvement");
    }

    /** A selling taker locks base and delivers exactly that; a buying maker fills at its own
     *  price; a market taker has no limit. None of them has anything to release. */
    @Test
    void recordTrades_releasesNothingWhenThereIsNoImprovement() {
        realNotional();
        when(spotTrades.findExistingSequences(any(), any())).thenReturn(List.of());
        TradeExecuted sellingTaker = tradeExecuted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                com.exchange.contracts.spot.v1.Side.SIDE_SELL, 7, 7).toBuilder().setTakerLimitPrice(PRICE / 2).build();
        TradeExecuted buyingTakerAtLimit = tradeExecuted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                com.exchange.contracts.spot.v1.Side.SIDE_BUY, 7, 7).toBuilder().setTakerLimitPrice(PRICE).build();
        TradeExecuted marketTaker = tradeExecuted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                com.exchange.contracts.spot.v1.Side.SIDE_BUY, 7, 7);

        for (TradeExecuted trade : List.of(sellingTaker, buyingTakerAtLimit, marketTaker)) {
            List<BalanceMutation> mutations = service.recordTrades(symbol,
                    List.of(new TradeToRecord(trade, SEQUENCE, Instant.now())));
            assertThat(mutations).noneMatch(m -> m.kind() == BalanceMutation.Kind.UNLOCK);
        }
    }

    private static TradeExecuted tradeExecuted(UUID tradeId, UUID buyerOrderId, UUID sellerOrderId,
                                               com.exchange.contracts.spot.v1.Side takerSide,
                                               int takerFeeBps, int makerFeeBps) {
        boolean takerIsBuyer = takerSide == com.exchange.contracts.spot.v1.Side.SIDE_BUY;
        return TradeExecuted.newBuilder()
                .setTradeId(bytes(tradeId))
                .setTakerOrderId(bytes(takerIsBuyer ? buyerOrderId : sellerOrderId))
                .setMakerOrderId(bytes(takerIsBuyer ? sellerOrderId : buyerOrderId))
                .setTakerUserId(takerIsBuyer ? BUYER_ID : SELLER_ID)
                .setMakerUserId(takerIsBuyer ? SELLER_ID : BUYER_ID)
                .setTakerSide(takerSide)
                .setPrice(PRICE)
                .setQuantity(QUANTITY)
                .setQuoteQuantity(PRICE) // QUANTITY == 1.0, so quoteQuantity == price exactly
                .setTakerFeeBps(takerFeeBps)
                .setMakerFeeBps(makerFeeBps)
                .setTakerStatus(com.exchange.contracts.spot.v1.OrderStatus.ORDER_STATUS_FILLED)
                .setMakerStatus(com.exchange.contracts.spot.v1.OrderStatus.ORDER_STATUS_FILLED)
                .build();
    }

    private static com.exchange.contracts.spot.v1.OrderRejected rejected(UUID orderId) {
        return com.exchange.contracts.spot.v1.OrderRejected.newBuilder()
                .setOrderId(bytes(orderId))
                .setUserId(BUYER_ID)
                .setReason(com.exchange.contracts.spot.v1.RejectReason.REJECT_REASON_SELF_TRADE)
                .setMessage("self-trade")
                .build();
    }

    private static com.exchange.contracts.spot.v1.OrderCanceled canceled(UUID orderId) {
        return com.exchange.contracts.spot.v1.OrderCanceled.newBuilder()
                .setOrderId(bytes(orderId))
                .setUserId(SELLER_ID)
                .setReason(com.exchange.contracts.spot.v1.CancelReason.CANCEL_REASON_USER_REQUESTED)
                .build();
    }

    private static ByteString bytes(UUID id) {
        return ByteString.copyFrom(Ulid.toBytes(id));
    }
}
