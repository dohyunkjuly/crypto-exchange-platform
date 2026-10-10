package com.exchange.spot.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.exchange.fee.FeeProduct;
import com.exchange.fee.FeeQueryService;
import com.exchange.spot.limit.exchange.SpotExchangeOrderLimits;
import com.exchange.spot.limit.exchange.SpotExchangeOrderLimitsRepository;
import com.exchange.spot.limit.override.SpotUserOrderLimitOverrideRepository;
import com.exchange.spot.limit.symbol.SpotSymbolUserOrderLimitOverrideRepository;
import com.exchange.spot.order.dto.OrderDetail;
import com.exchange.spot.order.exception.AlgoOrderLimitExceededException;
import com.exchange.spot.order.exception.InvalidStopPriceException;
import com.exchange.spot.order.exception.InvalidTrailingDeltaException;
import com.exchange.spot.order.exception.OrderLimitExceededException;
import com.exchange.spot.order.exception.QuoteOrderQtyNotSupportedException;
import com.exchange.spot.order.exception.QuoteOrderQtyRequiredException;
import com.exchange.spot.order.exception.TimeInForceNotSupportedException;
import com.exchange.spot.order.exception.EngineUnavailableException;
import com.exchange.spot.order.exception.SymbolNotTradingException;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.spot.symbol.SymbolStatus;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.ReferenceType;
import com.exchange.contracts.spot.v1.CancelAllOrders;
import com.exchange.contracts.spot.v1.CancelScope;
import com.exchange.contracts.spot.v1.Command;
import com.exchange.spot.outbox.CommandOutboxService;
import com.exchange.spot.outbox.CommandOutboxPublisher;
import com.exchange.spot.outbox.SpotCommandOutbox;
import com.exchange.spot.outbox.SpotCommandOutboxRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Every collaborator is mocked -- {@code BalanceServiceTest} style. The point is
 * {@code OrderService}'s own orchestration (which asset/amount it locks, which limit
 * it checks, when it saves vs. rejects vs. replays), not JPA or Kafka themselves.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final Long USER_ID = 1L;
    private static final Long BASE_ASSET_ID = 10L;
    private static final Long QUOTE_ASSET_ID = 20L;

    private static final long PRICE_TICK = 100_000_000L;      // $1.00
    private static final long QTY_STEP = 1_000_000L;          // 0.01
    private static final long MIN_QTY = 1_000_000L;           // 0.01
    private static final long MAX_QTY = 100_000_000_000L;     // 1000
    private static final long MIN_NOTIONAL = 100_000_000L;    // $1.00

    @Mock
    private SpotOrderRepository spotOrders;
    @Mock
    private SpotSymbolRepository spotSymbols;
    @Mock
    private SpotExchangeOrderLimitsRepository exchangeLimits;
    @Mock
    private SpotUserOrderLimitOverrideRepository userLimitOverrides;
    @Mock
    private SpotSymbolUserOrderLimitOverrideRepository symbolUserLimitOverrides;
    @Mock
    private FeeQueryService feeQuery;
    @Mock
    private BalanceService balanceService;
    @Mock
    private KafkaTemplate<String, byte[]> kafka;
    @Mock
    private com.exchange.spot.symbol.CommandPartitions commandPartitions;
    @Mock
    private com.exchange.spot.symbol.EnginePresence enginePresence;
    @Mock
    private SpotCommandOutboxRepository outboxRows;
    @Mock
    private SubAccountOrderHook subAccountHook;

    private OrderService orderService;
    private SpotSymbol symbol;

    @BeforeEach
    void setUp() {
        // A real outbox over a mocked table, a publisher with no lanes (so a hand-off is sent on
        // this thread) and a transaction manager that does nothing: the service still runs
        // "transaction, then hand off", and a test sees every command land on the Kafka mock.
        orderService = new OrderService(spotOrders, spotSymbols, exchangeLimits,
                userLimitOverrides, symbolUserLimitOverrides, feeQuery, balanceService, kafka,
                new CommandOutboxService(outboxRows, kafka, new CommandOutboxPublisher(outboxRows, kafka, 0, 10, 500)),
                commandPartitions, enginePresence, Optional.of(subAccountHook),
                mock(PlatformTransactionManager.class));
        lenient().when(enginePresence.isLive(anyInt())).thenReturn(true);

        symbol = new SpotSymbol(SYMBOL, BASE_ASSET_ID, QUOTE_ASSET_ID,
                PRICE_TICK, QTY_STEP, MIN_QTY, MAX_QTY, MIN_NOTIONAL, 3);
        symbol.setStatus(SymbolStatus.TRADING);
        symbol.setMarketOrdersEnabled(true);
        symbol.setStopOrdersEnabled(true);
        lenient().when(spotSymbols.findBySymbol(SYMBOL)).thenReturn(Optional.of(symbol));

        SpotExchangeOrderLimits defaults = mock(SpotExchangeOrderLimits.class);
        lenient().when(defaults.getMaxNumOrders()).thenReturn(200);
        lenient().when(defaults.getMaxNumAlgoOrders()).thenReturn(50);
        lenient().when(exchangeLimits.findById(SpotExchangeOrderLimits.SINGLETON_ID))
                .thenReturn(Optional.of(defaults));

        // -2 maker (a rebate) and 7 taker, as the base tier used to supply -- resolution
        // itself is FeeQueryServiceTest's subject now.
        lenient().when(feeQuery.ratesFor(any(), any(), eq(FeeProduct.SPOT)))
                .thenReturn(new com.exchange.fee.FeeRates(-2, 7));

        lenient().when(kafka.send(anyString(), anyInt(), anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    private UUID limit(Side side, String price, String quantity) {
        return orderService.placeOrder(USER_ID, SYMBOL, side, OrderType.LIMIT, TimeInForce.GTC,
                price, null, quantity, null, null, null, null).order().getId();
    }

    private UUID market(Side side, String quantity, String quoteOrderQty) {
        return orderService.placeOrder(USER_ID, SYMBOL, side, OrderType.MARKET, null,
                null, null, quantity, quoteOrderQty, null, null, null).order().getId();
    }

    private UUID stop(Side side, OrderType type, String stopPrice, String quantity,
                      Integer trailingDeltaBps) {
        return orderService.placeOrder(USER_ID, SYMBOL, side, type, TimeInForce.GTC,
                null, stopPrice, quantity, null, null, null, trailingDeltaBps).order().getId();
    }

    @Test
    void limit_buy_locks_price_times_quantity() {
        limit(Side.BUY, "63250.00", "0.5");

        // 63250.00 * 0.5 = 31625.00
        verify(balanceService).lock(eq(USER_ID), eq(QUOTE_ASSET_ID), eq(3_162_500_000_000L),
                any(ReferenceType.class), anyString(), anyString());
        verify(spotOrders).save(any(SpotOrder.class));
        verify(kafka).send(anyString(), anyInt(), anyString(), any(byte[].class));
    }

    @Test
    void limit_sell_locks_quantity() {
        limit(Side.SELL, "63250.00", "0.5");

        verify(balanceService).lock(eq(USER_ID), eq(BASE_ASSET_ID), eq(50_000_000L),
                any(ReferenceType.class), anyString(), anyString());
    }

    @Test
    void market_buy_without_quote_order_qty_is_rejected() {
        assertThatThrownBy(() -> market(Side.BUY, "0.5", null))
                .isInstanceOf(QuoteOrderQtyRequiredException.class);
        verify(balanceService, never()).lock(any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    void market_buy_with_both_quantity_and_quote_order_qty_is_rejected() {
        assertThatThrownBy(() -> market(Side.BUY, "0.5", "500.00"))
                .isInstanceOf(QuoteOrderQtyNotSupportedException.class);
    }

    @Test
    void market_buy_locks_quote_order_qty_exactly() {
        market(Side.BUY, null, "500.00");

        verify(balanceService).lock(eq(USER_ID), eq(QUOTE_ASSET_ID), eq(50_000_000_000L),
                any(ReferenceType.class), anyString(), anyString());
    }

    @Test
    void market_sell_locks_quantity() {
        market(Side.SELL, "0.5", null);

        verify(balanceService).lock(eq(USER_ID), eq(BASE_ASSET_ID), eq(50_000_000L),
                any(ReferenceType.class), anyString(), anyString());
    }

    @Test
    void market_sell_with_quote_order_qty_is_rejected() {
        assertThatThrownBy(() -> market(Side.SELL, null, "500.00"))
                .isInstanceOf(QuoteOrderQtyNotSupportedException.class);
    }

    @Test
    void a_conditional_order_with_no_stop_price_and_no_trailing_is_rejected() {
        assertThatThrownBy(() -> stop(Side.SELL, OrderType.STOP_LOSS, null, "0.5", null))
                .isInstanceOf(InvalidStopPriceException.class);
    }

    @Test
    void a_trailing_stop_with_no_activation_price_is_accepted() {
        // No stopPrice -- trailingDeltaBps alone is enough, activation is optional.
        stop(Side.SELL, OrderType.STOP_LOSS, null, "0.5", 100);

        verify(spotOrders).save(any(SpotOrder.class));
    }

    @Test
    void a_conditional_order_saves_with_pending_trigger_status() {
        stop(Side.SELL, OrderType.STOP_LOSS, "60000.00", "0.5", null);

        ArgumentCaptor<SpotOrder> captor = ArgumentCaptor.forClass(SpotOrder.class);
        verify(spotOrders).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OrderStatus.PENDING_TRIGGER);
    }

    @Test
    void a_limit_order_saves_with_new_status() {
        limit(Side.BUY, "63250.00", "0.5");

        ArgumentCaptor<SpotOrder> captor = ArgumentCaptor.forClass(SpotOrder.class);
        verify(spotOrders).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OrderStatus.NEW);
    }

    @Test
    void an_out_of_bounds_trailing_delta_is_rejected() {
        assertThatThrownBy(() -> stop(Side.SELL, OrderType.STOP_LOSS, "60000.00", "0.5", 1))
                .isInstanceOf(InvalidTrailingDeltaException.class);
    }

    @Test
    void gtd_is_rejected() {
        assertThatThrownBy(() -> orderService.placeOrder(USER_ID, SYMBOL, Side.BUY,
                OrderType.LIMIT, TimeInForce.GTD, "63250.00", null, "0.5", null, null, null, null))
                .isInstanceOf(TimeInForceNotSupportedException.class);
    }

    @Test
    void plain_order_limit_exceeded_is_rejected_before_locking_anything() {
        when(spotOrders.countByUserIdAndSymbolIdAndStatusIn(eq(USER_ID), any(), any()))
                .thenReturn(200L);

        assertThatThrownBy(() -> limit(Side.BUY, "63250.00", "0.5"))
                .isInstanceOf(OrderLimitExceededException.class);
        verify(balanceService, never()).lock(any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    void algo_order_limit_exceeded_is_distinct_from_the_plain_order_limit() {
        // Plain limit has headroom; the algo-specific cap is what's exhausted.
        when(spotOrders.countByUserIdAndSymbolIdAndOrderTypeInAndStatusIn(
                eq(USER_ID), any(), any(), any())).thenReturn(50L);

        assertThatThrownBy(() -> stop(Side.SELL, OrderType.STOP_LOSS, "60000.00", "0.5", null))
                .isInstanceOf(AlgoOrderLimitExceededException.class);
    }

    @Test
    void replaying_the_same_client_order_id_returns_the_original_without_locking_again() {
        SpotOrder existing = new SpotOrder(UUID.randomUUID(), USER_ID, 1L, Side.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 6_325_000_000_000L, null, 50_000_000L, null,
                -2, 7, "retry-1", null);
        when(spotOrders.findByUserIdAndClientOrderId(USER_ID, "retry-1"))
                .thenReturn(Optional.of(existing));

        OrderDetail placed = orderService.placeOrder(USER_ID, SYMBOL, Side.BUY, OrderType.LIMIT,
                TimeInForce.GTC, "63250.00", null, "0.5", null, "retry-1", null, null);

        assertThat(placed.order()).isSameAs(existing);
        verify(balanceService, never()).lock(any(), any(), anyLong(), any(), any(), any());
        verify(spotOrders, never()).save(any());
        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any(byte[].class));
    }

    /** The order is recorded whether or not Kafka answers: the command is a row in the same
     *  transaction, and a failed publish leaves that row for the sweeper. Failing the request
     *  instead is what used to leave the engine with orders the database had rolled back. */
    @Test
    void a_kafka_publish_failure_leaves_the_order_recorded_and_the_command_unpublished() {
        CompletableFuture<SendResult<String, byte[]>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unreachable"));
        when(kafka.send(anyString(), anyInt(), anyString(), any(byte[].class))).thenReturn(failed);

        UUID orderId = limit(Side.BUY, "63250.00", "0.5");

        verify(spotOrders).save(any());
        verify(outboxRows).save(argThat(row -> orderId.equals(row.getOrderId())));
        verify(outboxRows, never()).markPublishedAll(any(), any());
    }

    @Test
    void placing_writes_the_command_to_the_outbox_and_marks_it_once_the_broker_has_it() throws Exception {
        UUID orderId = limit(Side.BUY, "63250.00", "0.5");

        ArgumentCaptor<SpotCommandOutbox> row = ArgumentCaptor.forClass(SpotCommandOutbox.class);
        verify(outboxRows).save(row.capture());
        Command command = Command.parseFrom(row.getValue().getPayload());
        assertThat(row.getValue().getOrderId()).isEqualTo(orderId);
        assertThat(row.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(row.getValue().getSymbol()).isEqualTo(SYMBOL);
        assertThat(row.getValue().getId())
                .as("the row is the command: one id, so a re-send is a repeat to the engine")
                .isEqualTo(com.exchange.common.id.Ulid.toUuid(command.getCommandId().toByteArray()));
        assertThat(command.hasPlaceOrder()).isTrue();
        verify(outboxRows).markPublishedAll(eq(List.of(row.getValue().getId())), any());
    }

    /** A cancel sent straight to Kafka would overtake a place still in the outbox, and the
     *  engine answers a cancel for an order it has never seen with silence. */
    @Test
    void a_cancel_queues_behind_the_orders_own_unpublished_place() throws Exception {
        SpotOrder open = openOrder(1L);
        ReflectionTestUtils.setField(symbol, "id", 1L);
        when(spotOrders.findById(open.getId())).thenReturn(Optional.of(open));
        when(spotSymbols.findById(1L)).thenReturn(Optional.of(symbol));
        when(outboxRows.hasPendingForOrder(open.getId())).thenReturn(true);
        when(outboxRows.hasOtherPendingForOrder(eq(open.getId()), any())).thenReturn(true);
        when(outboxRows.hasOlderPendingForOrder(eq(open.getId()), any(), any())).thenReturn(true);

        orderService.cancelOrder(USER_ID, open.getId());

        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any(byte[].class));
        ArgumentCaptor<SpotCommandOutbox> row = ArgumentCaptor.forClass(SpotCommandOutbox.class);
        verify(outboxRows).save(row.capture());
        assertThat(Command.parseFrom(row.getValue().getPayload()).hasCancelOrder()).isTrue();
    }

    @Test
    void a_cancel_with_nothing_pending_goes_straight_to_kafka_and_writes_nothing() {
        SpotOrder open = openOrder(1L);
        ReflectionTestUtils.setField(symbol, "id", 1L);
        when(spotOrders.findById(open.getId())).thenReturn(Optional.of(open));
        when(spotSymbols.findById(1L)).thenReturn(Optional.of(symbol));

        orderService.cancelOrder(USER_ID, open.getId());

        verify(kafka).send(anyString(), anyInt(), anyString(), any(byte[].class));
        verify(outboxRows, never()).save(any());
    }

    @Test
    void a_cancel_all_queues_when_anything_of_the_users_is_unpublished() throws Exception {
        ReflectionTestUtils.setField(symbol, "id", 1L);
        when(spotOrders.findByUserIdAndStatusIn(eq(USER_ID), any())).thenReturn(List.of(openOrder(1L)));
        when(spotSymbols.findAllById(any())).thenReturn(List.of(symbol));
        when(outboxRows.hasPendingForUser(USER_ID)).thenReturn(true);
        when(outboxRows.hasOtherPendingForUser(eq(USER_ID), any())).thenReturn(true);
        when(outboxRows.hasOlderPendingForUser(eq(USER_ID), any(), any())).thenReturn(true);

        assertThat(orderService.cancelAllOrders(USER_ID, null, null, null, OpenOrderScope.ALL)).isEqualTo(1);

        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any(byte[].class));
        ArgumentCaptor<SpotCommandOutbox> row = ArgumentCaptor.forClass(SpotCommandOutbox.class);
        verify(outboxRows).save(row.capture());
        assertThat(row.getValue().getOrderId()).as("a cancel-all names a user, not an order").isNull();
        assertThat(Command.parseFrom(row.getValue().getPayload()).hasCancelAllOrders()).isTrue();
    }

    @Test
    void cancel_all_sends_one_command_per_market_carrying_the_callers_user_id() throws Exception {
        SpotSymbol eth = new SpotSymbol("ETHUSDT", 30L, QUOTE_ASSET_ID,
                PRICE_TICK, QTY_STEP, MIN_QTY, MAX_QTY, MIN_NOTIONAL, 1);
        ReflectionTestUtils.setField(symbol, "id", 1L);
        ReflectionTestUtils.setField(eth, "id", 2L);
        when(spotOrders.findByUserIdAndStatusIn(eq(USER_ID), any())).thenReturn(List.of(
                openOrder(1L), openOrder(1L), openOrder(2L)));
        when(spotSymbols.findAllById(any())).thenReturn(List.of(symbol, eth));

        assertThat(orderService.cancelAllOrders(USER_ID, null, null, null, OpenOrderScope.ALL)).isEqualTo(3);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> values = ArgumentCaptor.forClass(byte[].class);
        verify(kafka, org.mockito.Mockito.times(2)).send(anyString(), anyInt(), keys.capture(), values.capture());
        assertThat(keys.getAllValues()).containsExactlyInAnyOrder(SYMBOL, "ETHUSDT");
        Set<String> symbols = new HashSet<>();
        for (byte[] bytes : values.getAllValues()) {
            Command command = Command.parseFrom(bytes);
            assertThat(command.hasCancelAllOrders()).isTrue();
            assertThat(command.getCancelAllOrders().getUserId()).isEqualTo(USER_ID);
            symbols.add(command.getSymbol());
        }
        assertThat(symbols).containsExactlyInAnyOrder(SYMBOL, "ETHUSDT");
    }

    @Test
    void cancel_all_with_nothing_open_sends_nothing() {
        when(spotOrders.findByUserIdAndStatusIn(eq(USER_ID), any())).thenReturn(List.of());

        assertThat(orderService.cancelAllOrders(USER_ID, null, null, null, OpenOrderScope.ALL)).isZero();
        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any(byte[].class));
    }

    /** The filters go on the command, not into a cancel per order: the engine is the one
     *  that can still see an order placed after the rows were read. */
    @Test
    void a_filtered_cancel_all_sends_one_bulk_command_carrying_the_filters() throws Exception {
        ReflectionTestUtils.setField(symbol, "id", 1L);
        SpotOrder buyLimit = openOrder(1L);
        SpotOrder sellLimit = openOrder(1L, Side.SELL, OrderType.LIMIT, OrderStatus.NEW);
        SpotOrder buyStop = openOrder(1L, Side.BUY, OrderType.STOP_LOSS_LIMIT, OrderStatus.PENDING_TRIGGER);
        when(spotOrders.findByUserIdAndStatusIn(eq(USER_ID), any()))
                .thenReturn(List.of(buyLimit, sellLimit, buyStop));
        when(spotSymbols.findAllById(any())).thenReturn(List.of(symbol));

        // The book tab, buys only: the parked stop and the sell are not counted.
        assertThat(orderService.cancelAllOrders(USER_ID, null, Side.BUY, null, OpenOrderScope.BOOK)).isEqualTo(1);
        CancelAllOrders bookBuys = onlyCancelAll();
        assertThat(bookBuys.getSide()).isEqualTo(com.exchange.contracts.spot.v1.Side.SIDE_BUY);
        assertThat(bookBuys.getScope()).isEqualTo(CancelScope.CANCEL_SCOPE_BOOK);
        assertThat(bookBuys.getOrderType())
                .isEqualTo(com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_UNSPECIFIED);

        // The trigger tab: no side, so both sides of the trigger book.
        org.mockito.Mockito.clearInvocations(kafka);
        assertThat(orderService.cancelAllOrders(USER_ID, null, null, null, OpenOrderScope.TRIGGERS)).isEqualTo(1);
        CancelAllOrders triggers = onlyCancelAll();
        assertThat(triggers.getScope()).isEqualTo(CancelScope.CANCEL_SCOPE_TRIGGERS);
        assertThat(triggers.getSide()).isEqualTo(com.exchange.contracts.spot.v1.Side.SIDE_UNSPECIFIED);

        // One order type, either book.
        org.mockito.Mockito.clearInvocations(kafka);
        assertThat(orderService.cancelAllOrders(USER_ID, null, null, OrderType.STOP_LOSS_LIMIT,
                OpenOrderScope.ALL)).isEqualTo(1);
        CancelAllOrders stops = onlyCancelAll();
        assertThat(stops.getOrderType())
                .isEqualTo(com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_STOP_LOSS_LIMIT);
        assertThat(stops.getScope()).isEqualTo(CancelScope.CANCEL_SCOPE_UNSPECIFIED);
    }

    /** Unfiltered, every filter field stays UNSPECIFIED -- the same bytes an engine that
     *  predates the filters would have read. */
    @Test
    void an_unfiltered_cancel_all_sets_no_filter() throws Exception {
        ReflectionTestUtils.setField(symbol, "id", 1L);
        when(spotOrders.findByUserIdAndStatusIn(eq(USER_ID), any())).thenReturn(List.of(openOrder(1L)));
        when(spotSymbols.findAllById(any())).thenReturn(List.of(symbol));

        orderService.cancelAllOrders(USER_ID, null, null, null, OpenOrderScope.ALL);

        CancelAllOrders request = onlyCancelAll();
        assertThat(request.getSide()).isEqualTo(com.exchange.contracts.spot.v1.Side.SIDE_UNSPECIFIED);
        assertThat(request.getOrderType())
                .isEqualTo(com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_UNSPECIFIED);
        assertThat(request.getScope()).isEqualTo(CancelScope.CANCEL_SCOPE_UNSPECIFIED);
        assertThat(request.getUserId()).isEqualTo(USER_ID);
    }

    @Test
    void a_cancel_all_for_an_unknown_pair_sends_nothing() {
        when(spotSymbols.findBySymbol("NOPEUSDT")).thenReturn(Optional.empty());

        assertThat(orderService.cancelAllOrders(USER_ID, "NOPEUSDT", null, null, OpenOrderScope.ALL)).isZero();
        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any(byte[].class));
    }

    /** The single bulk cancel published, failing if the call sent a cancel per order instead. */
    private CancelAllOrders onlyCancelAll() throws Exception {
        ArgumentCaptor<byte[]> values = ArgumentCaptor.forClass(byte[].class);
        verify(kafka, org.mockito.Mockito.atLeast(0)).send(anyString(), anyInt(), anyString(), values.capture());
        assertThat(values.getAllValues()).hasSize(1);
        Command command = Command.parseFrom(values.getValue());
        assertThat(command.hasCancelAllOrders())
                .as("a filtered cancel-all is still one command per market").isTrue();
        return command.getCancelAllOrders();
    }

    /** Refused before anything is locked or saved: a market that cannot publish takes no money. */
    @Test
    void a_market_whose_partition_does_not_exist_takes_no_order_and_locks_nothing() {
        org.mockito.Mockito.doThrow(new com.exchange.spot.order.exception.MarketUnavailableException(SYMBOL))
                .when(commandPartitions).requireExists(symbol);

        assertThatThrownBy(() -> limit(Side.BUY, "63250.00", "0.5"))
                .isInstanceOf(com.exchange.spot.order.exception.MarketUnavailableException.class);
        verify(balanceService, never()).lock(any(), any(), anyLong(), any(), any(), any());
        verify(spotOrders, never()).save(any());
        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any(byte[].class));
    }

    /** Settlement cannot record a trade with one user on both sides, so an unset mode still prevents one. */
    @Test
    void self_trade_prevention_defaults_to_expire_maker() throws Exception {
        limit(Side.BUY, "63250.00", "0.5");
        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(kafka).send(anyString(), anyInt(), anyString(), sent.capture());
        assertThat(Command.parseFrom(sent.getValue()).getPlaceOrder().getSelfTradePreventionMode())
                .isEqualTo(com.exchange.contracts.spot.v1.SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_MAKER);
    }

    /** FOK must leave the book untouched, and EXPIRE_BOTH asks to cancel the resting order. */
    @Test
    void fok_with_expire_both_is_refused_before_anything_locks() {
        assertThatThrownBy(() -> orderService.placeOrder(USER_ID, SYMBOL, Side.BUY, OrderType.LIMIT, TimeInForce.FOK,
                "63250.00", null, "0.5", null, null,
                com.exchange.contracts.spot.v1.SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_BOTH, null))
                .isInstanceOf(com.exchange.spot.order.exception.FokWithExpireBothNotSupportedException.class);
        verify(balanceService, never()).lock(any(), any(), anyLong(), any(), any(), any());
        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any(byte[].class));
    }

    @Test
    void fok_with_expire_taker_is_accepted() {
        orderService.placeOrder(USER_ID, SYMBOL, Side.BUY, OrderType.LIMIT, TimeInForce.FOK,
                "63250.00", null, "0.5", null, null,
                com.exchange.contracts.spot.v1.SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_TAKER, null);
        verify(kafka).send(anyString(), anyInt(), anyString(), any(byte[].class));
    }

    /** The setup market is on partition 3, where a hash of "BTCUSDT" would not put it (2). */
    @Test
    void a_place_and_its_cancel_go_to_the_markets_configured_partition() {
        ReflectionTestUtils.setField(symbol, "id", 1L);
        UUID orderId = limit(Side.BUY, "63250.00", "0.5");
        verify(kafka).send(eq(com.exchange.common.kafka.Topics.SPOT_COMMANDS), eq(3), eq(SYMBOL), any(byte[].class));

        SpotOrder placed = openOrder(1L);
        ReflectionTestUtils.setField(placed, "id", orderId);
        when(spotOrders.findById(orderId)).thenReturn(Optional.of(placed));
        when(spotSymbols.findById(1L)).thenReturn(Optional.of(symbol));
        org.mockito.Mockito.clearInvocations(kafka);

        orderService.cancelOrder(USER_ID, orderId);
        verify(kafka).send(eq(com.exchange.common.kafka.Topics.SPOT_COMMANDS), eq(3), eq(SYMBOL), any(byte[].class));
    }

    private static SpotOrder openOrder(Long symbolId, Side side, OrderType type, OrderStatus status) {
        SpotOrder order = new SpotOrder(UUID.randomUUID(), USER_ID, symbolId, side, type,
                TimeInForce.GTC, 100_000_000L, type == OrderType.LIMIT ? null : 100_000_000L,
                100_000_000L, null, -2, 7, null, null);
        order.setStatus(status);
        return order;
    }

    private static SpotOrder openOrder(Long symbolId) {
        return new SpotOrder(UUID.randomUUID(), USER_ID, symbolId, Side.BUY, OrderType.LIMIT,
                TimeInForce.GTC, 100_000_000L, null, 100_000_000L, null, -2, 7, null, null);
    }

    // ------------------------------------------------------------------ amend

    private static final long ONE = 100_000_000L;

    /** A resting LIMIT BUY of 2 @ $100 for USER_ID, on the setup market. */
    private SpotOrder resting(Side side, OrderType type, TimeInForce tif) {
        ReflectionTestUtils.setField(symbol, "id", 1L);
        SpotOrder order = new SpotOrder(UUID.randomUUID(), USER_ID, 1L, side, type, tif,
                100 * ONE, null, 2 * ONE, null, -2, 7, null, null);
        lenient().when(spotOrders.findById(order.getId())).thenReturn(Optional.of(order));
        lenient().when(spotSymbols.findById(1L)).thenReturn(Optional.of(symbol));
        lenient().when(spotOrders.claimAmend(eq(order.getId()), any())).thenReturn(1);
        return order;
    }

    private com.exchange.contracts.spot.v1.AmendOrder sentAmend() throws Exception {
        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(kafka).send(anyString(), anyInt(), anyString(), sent.capture());
        return Command.parseFrom(sent.getValue()).getAmendOrder();
    }

    @Test
    void a_higher_buy_price_locks_the_difference_first_and_tells_the_engine_how_much() throws Exception {
        SpotOrder order = resting(Side.BUY, OrderType.LIMIT, TimeInForce.GTC);

        orderService.amendOrder(USER_ID, order.getId(), "110.00", null, null, null);

        // 2 @ 110 needs 220, 2 @ 100 holds 200.
        verify(balanceService).lock(eq(USER_ID), eq(QUOTE_ASSET_ID), eq(20 * ONE), any(), any(), any());
        assertThat(sentAmend().getAdditionalLocked()).isEqualTo(20 * ONE);
        assertThat(sentAmend().getNewPrice()).isEqualTo(110 * ONE);
        assertThat(sentAmend().hasNewQuantity()).isFalse();
    }

    /** No engine on the market's partition: refuse now, lock nothing, write nothing. */
    @Test
    void an_order_for_a_partition_no_engine_is_running_is_refused_before_anything_locks() {
        when(enginePresence.isLive(3)).thenReturn(false);

        assertThatThrownBy(() -> limit(Side.BUY, "100.00", "1.00"))
                .isInstanceOf(EngineUnavailableException.class);
        verify(balanceService, never()).lock(any(), any(), anyLong(), any(), any(), any());
        verify(spotOrders, never()).save(any());
        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any());
    }

    @Test
    void an_amend_for_a_partition_no_engine_is_running_is_refused_before_anything_locks() {
        SpotOrder order = resting(Side.BUY, OrderType.LIMIT, TimeInForce.GTC);
        when(enginePresence.isLive(3)).thenReturn(false);

        assertThatThrownBy(() -> orderService.amendOrder(USER_ID, order.getId(), "110.00", null, null, null))
                .isInstanceOf(EngineUnavailableException.class);
        verify(balanceService, never()).lock(any(), any(), anyLong(), any(), any(), any());
        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any());
    }

    /** The one thing a trader must always be able to do while the engine is away: the cancel
     *  path never even asks whether one is there. */
    @Test
    void a_cancel_is_never_refused_for_a_silent_engine() {
        SpotOrder open = openOrder(1L);
        ReflectionTestUtils.setField(symbol, "id", 1L);
        when(spotOrders.findById(open.getId())).thenReturn(Optional.of(open));
        when(spotSymbols.findById(1L)).thenReturn(Optional.of(symbol));

        orderService.cancelOrder(USER_ID, open.getId());

        verify(kafka).send(anyString(), anyInt(), anyString(), any(byte[].class));
        verifyNoInteractions(enginePresence);
    }

    /** An amend can cross the book and trade, so a market closed to new orders refuses it. */
    @Test
    void an_amend_in_a_cancel_only_market_is_refused_before_anything_locks() {
        SpotOrder order = resting(Side.BUY, OrderType.LIMIT, TimeInForce.GTC);
        symbol.setStatus(SymbolStatus.CANCEL_ONLY);

        assertThatThrownBy(() -> orderService.amendOrder(USER_ID, order.getId(), "110.00", null, null, null))
                .isInstanceOf(SymbolNotTradingException.class);
        verify(balanceService, never()).lock(any(), any(), anyLong(), any(), any(), any());
        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any());
    }

    @Test
    void a_decrease_locks_nothing_and_leaves_the_release_to_settlement() throws Exception {
        SpotOrder order = resting(Side.BUY, OrderType.LIMIT, TimeInForce.GTC);

        orderService.amendOrder(USER_ID, order.getId(), "90.00", null, "1", null);

        verify(balanceService, never()).lock(any(), any(), anyLong(), any(), any(), any());
        assertThat(sentAmend().getAdditionalLocked()).isZero();
    }

    /** 2 @ 100 to 10 @ 50. From the row (nothing filled) the extra is 500 - 200 = 300, but if
     *  the whole 2 fills first the engine's amend needs 8 @ 50 = 400 with nothing left held. */
    @Test
    void a_lower_price_with_a_larger_size_locks_for_the_worst_fill_in_flight() {
        SpotOrder order = resting(Side.BUY, OrderType.LIMIT, TimeInForce.GTC);

        orderService.amendOrder(USER_ID, order.getId(), "50.00", null, "10", null);

        verify(balanceService).lock(eq(USER_ID), eq(QUOTE_ASSET_ID), eq(400 * ONE), any(), any(), any());
    }

    @Test
    void a_larger_sell_locks_the_extra_base_quantity() {
        SpotOrder order = resting(Side.SELL, OrderType.LIMIT, TimeInForce.GTC);

        orderService.amendOrder(USER_ID, order.getId(), null, null, "3", null);

        verify(balanceService).lock(eq(USER_ID), eq(BASE_ASSET_ID), eq(ONE), any(), any(), any());
    }

    @Test
    void someone_elses_order_is_not_found_and_nothing_is_sent() {
        SpotOrder order = resting(Side.BUY, OrderType.LIMIT, TimeInForce.GTC);

        assertThatThrownBy(() -> orderService.amendOrder(2L, order.getId(), "110.00", null, null, null))
                .isInstanceOf(com.exchange.spot.order.exception.OrderNotFoundException.class);
        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any(byte[].class));
    }

    @Test
    void a_second_amend_while_one_is_pending_is_refused_before_anything_locks() {
        SpotOrder order = resting(Side.BUY, OrderType.LIMIT, TimeInForce.GTC);
        when(spotOrders.claimAmend(eq(order.getId()), any())).thenReturn(0);
        when(spotOrders.isAmendPending(order.getId())).thenReturn(true);

        assertThatThrownBy(() -> orderService.amendOrder(USER_ID, order.getId(), "110.00", null, null, null))
                .isInstanceOf(com.exchange.spot.order.exception.AmendPendingException.class);
        verify(balanceService, never()).lock(any(), any(), anyLong(), any(), any(), any());
        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any(byte[].class));
    }

    @Test
    void insufficient_balance_sends_nothing() {
        SpotOrder order = resting(Side.BUY, OrderType.LIMIT, TimeInForce.GTC);
        when(balanceService.lock(any(), any(), anyLong(), any(), any(), any()))
                .thenThrow(new com.exchange.wallet.balance.exception.InsufficientBalanceException(USER_ID, QUOTE_ASSET_ID, 20, 0));

        assertThatThrownBy(() -> orderService.amendOrder(USER_ID, order.getId(), "110.00", null, null, null))
                .isInstanceOf(com.exchange.wallet.balance.exception.InsufficientBalanceException.class);
        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any(byte[].class));
    }

    @Test
    void terminal_market_and_ioc_orders_are_not_amendable() {
        SpotOrder filled = resting(Side.BUY, OrderType.LIMIT, TimeInForce.GTC);
        filled.setStatus(OrderStatus.FILLED);
        SpotOrder ioc = resting(Side.BUY, OrderType.LIMIT, TimeInForce.IOC);

        for (SpotOrder order : List.of(filled, ioc)) {
            assertThatThrownBy(() -> orderService.amendOrder(USER_ID, order.getId(), "110.00", null, null, null))
                    .isInstanceOf(com.exchange.spot.order.exception.OrderNotAmendableException.class);
        }
    }

    @Test
    void the_same_values_or_no_values_change_nothing() {
        SpotOrder order = resting(Side.BUY, OrderType.LIMIT, TimeInForce.GTC);

        assertThatThrownBy(() -> orderService.amendOrder(USER_ID, order.getId(), "100.00", null, "2", null))
                .isInstanceOf(com.exchange.spot.order.exception.NothingToAmendException.class);
        assertThatThrownBy(() -> orderService.amendOrder(USER_ID, order.getId(), null, null, null, null))
                .isInstanceOf(com.exchange.spot.order.exception.NothingToAmendException.class);
    }

    @Test
    void a_new_total_must_be_above_what_has_filled_and_pass_the_placement_checks() {
        SpotOrder order = resting(Side.BUY, OrderType.LIMIT, TimeInForce.GTC);
        order.applyFill(ONE, 100 * ONE);

        assertThatThrownBy(() -> orderService.amendOrder(USER_ID, order.getId(), null, null, "1", null))
                .isInstanceOf(com.exchange.spot.order.exception.InvalidQuantityException.class);
        assertThatThrownBy(() -> orderService.amendOrder(USER_ID, order.getId(), "100.50", null, null, null))
                .isInstanceOf(com.exchange.spot.order.exception.InvalidPriceException.class);
    }

    @Test
    void a_resting_order_has_no_stop_price_to_amend() {
        SpotOrder order = resting(Side.BUY, OrderType.LIMIT, TimeInForce.GTC);

        assertThatThrownBy(() -> orderService.amendOrder(USER_ID, order.getId(), null, "90.00", null, null))
                .isInstanceOf(com.exchange.spot.order.exception.FieldNotAmendableException.class);
    }

    @Test
    void a_trailing_order_takes_a_new_size_only() throws Exception {
        ReflectionTestUtils.setField(symbol, "id", 1L);
        SpotOrder trailing = new SpotOrder(UUID.randomUUID(), USER_ID, 1L, Side.SELL,
                OrderType.STOP_LOSS, TimeInForce.GTC, null, 90 * ONE, 2 * ONE, null, -2, 7, null, 100);
        trailing.setStatus(OrderStatus.PENDING_TRIGGER);
        when(spotOrders.findById(trailing.getId())).thenReturn(Optional.of(trailing));
        when(spotSymbols.findById(1L)).thenReturn(Optional.of(symbol));
        when(spotOrders.claimAmend(eq(trailing.getId()), any())).thenReturn(1);

        assertThatThrownBy(() -> orderService.amendOrder(USER_ID, trailing.getId(), null, "80.00", null, null))
                .isInstanceOf(com.exchange.spot.order.exception.FieldNotAmendableException.class);

        orderService.amendOrder(USER_ID, trailing.getId(), null, null, "3", null);
        assertThat(sentAmend().getNewQuantity()).isEqualTo(3 * ONE);
    }

    @Test
    @DisplayName("a master's order never asks the sub-account hook: the common path pays nothing for it")
    void a_masters_order_skips_the_hook() {
        limit(Side.BUY, "63250.00", "0.5");

        org.mockito.Mockito.verifyNoInteractions(subAccountHook);
    }

    @Test
    @DisplayName("a sub-account's order goes to the hook before its funds are locked, with what is about to be locked")
    void a_subs_order_passes_the_hook_before_the_lock() {
        orderService.placeOrder(USER_ID, 9L, SYMBOL, Side.BUY, OrderType.LIMIT, TimeInForce.GTC,
                "63250.00", null, "0.5", null, null, null, null);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(subAccountHook, balanceService);
        order.verify(subAccountHook).beforeLock(any(UUID.class), eq(USER_ID), eq(symbol), eq(Side.BUY),
                eq(3_162_500_000_000L), eq(50_000_000L));
        order.verify(balanceService).lock(eq(USER_ID), eq(QUOTE_ASSET_ID), eq(3_162_500_000_000L),
                any(ReferenceType.class), anyString(), anyString());
    }

    @Test
    @DisplayName("an order the hook refuses locks nothing, saves nothing and sends nothing")
    void a_refused_sub_order_leaves_nothing_behind() {
        org.mockito.Mockito.doThrow(new com.exchange.spot.order.exception.ManualOrderNotAllowedException("no"))
                .when(subAccountHook).beforeLock(any(), any(), any(), any(), anyLong(), anyLong());

        assertThatThrownBy(() -> orderService.placeOrder(USER_ID, 9L, SYMBOL, Side.SELL, OrderType.LIMIT,
                TimeInForce.GTC, "63250.00", null, "0.5", null, null, null, null))
                .isInstanceOf(com.exchange.spot.order.exception.ManualOrderNotAllowedException.class);

        verify(balanceService, never()).lock(any(), any(), anyLong(), any(), anyString(), anyString());
        verify(spotOrders, never()).save(any());
        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any(byte[].class));
    }

    @Test
    @DisplayName("the copier's order skips the hook, and its own record is written before anything is sent")
    void a_copy_order_skips_the_hook_and_records_before_the_send() {
        // A transaction manager that runs the work at once, as the mocked one does everywhere here.
        List<String> order = new java.util.ArrayList<>();
        when(kafka.send(anyString(), anyInt(), anyString(), any(byte[].class))).thenAnswer(call -> {
            order.add("sent");
            return CompletableFuture.completedFuture(mock(SendResult.class));
        });

        OrderDetail placed = orderService.placeCopyOrder(USER_ID, 9L, SYMBOL, Side.BUY, OrderType.LIMIT,
                TimeInForce.IOC, "63250.00", "0.5", made -> order.add("recorded " + made.order().getId()));

        org.mockito.Mockito.verifyNoInteractions(subAccountHook);
        assertThat(order).containsExactly("recorded " + placed.order().getId(), "sent");
        assertThat(placed.order().getTimeInForce()).isEqualTo(TimeInForce.IOC);
    }

    @Test
    @DisplayName("a copy whose record cannot be written is not placed: nothing is sent")
    void a_copy_order_whose_record_fails_sends_nothing() {
        assertThatThrownBy(() -> orderService.placeCopyOrder(USER_ID, 9L, SYMBOL, Side.BUY, OrderType.LIMIT,
                TimeInForce.IOC, "63250.00", "0.5", made -> {
                    throw new IllegalStateException("already copied");
                })).isInstanceOf(IllegalStateException.class);

        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any(byte[].class));
    }
}
