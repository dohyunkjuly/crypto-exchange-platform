package com.exchange.spot.copy.copy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

import com.exchange.common.id.Ulid;
import com.exchange.copy.follow.CopyCloseService;
import com.exchange.copy.settings.CopyQuoteAsset;
import com.exchange.copy.follow.CloseReason;
import com.exchange.copy.follow.CopyFollow;
import com.exchange.copy.follow.CopyFollowRepository;
import com.exchange.copy.follow.CopyMode;
import com.exchange.copy.follow.FollowStatus;
import com.exchange.copy.leadorder.CopyLeadOrder;
import com.exchange.copy.order.CopyOrder;
import com.exchange.copy.order.CopyOrderReason;
import com.exchange.copy.order.CopyOrderRepository;
import com.exchange.copy.order.CopyOrderStatus;
import com.exchange.copy.settings.CopySettings;
import com.exchange.copy.settings.CopySettingsRepository;
import com.exchange.spot.order.OrderService;
import com.exchange.spot.order.OrderType;
import com.exchange.spot.order.Side;
import com.exchange.spot.order.SpotOrder;
import com.exchange.spot.order.TimeInForce;
import com.exchange.spot.order.dto.OrderDetail;
import com.exchange.spot.order.exception.EngineUnavailableException;
import com.exchange.spot.order.exception.SymbolNotTradingException;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.user.eligibility.EligibilityService;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

/** Each follower gets one copy; what cannot be copied is recorded, counted, and at the limit closes the account. */
class CopyServiceTest {

    private static final long ONE = 100_000_000L;
    private static final long FOLLOWER = 1L;
    private static final long ACCOUNT = 2L;
    private static final long USDT = 3L;
    private static final long BTC = 4L;

    private final CopyFollowRepository follows = Mockito.mock(CopyFollowRepository.class);
    private final CopyOrderRepository copyOrders = Mockito.mock(CopyOrderRepository.class);
    private final CopySettingsRepository settings = Mockito.mock(CopySettingsRepository.class);
    private final SpotSymbolRepository markets = Mockito.mock(SpotSymbolRepository.class);
    private final BalanceRepository balances = Mockito.mock(BalanceRepository.class);
    private final UserRepository users = Mockito.mock(UserRepository.class);
    private final EligibilityService eligibility = Mockito.mock(EligibilityService.class);
    private final OrderService orderService = Mockito.mock(OrderService.class);
    private final CopyCloseService closes = Mockito.mock(CopyCloseService.class);
    private final AssetService assets = Mockito.mock(AssetService.class);
    private final CopyService service = new CopyService(follows, copyOrders, settings, markets, balances,
            users, eligibility, orderService, closes, new CopyQuoteAsset(assets, "USDT"));

    private final UUID leaderId = Ulid.randomUuid();
    private final CopyFollow follow = new CopyFollow(Ulid.randomUuid(), leaderId, FOLLOWER, ACCOUNT,
            CopyMode.FIXED_RATIO, null, 1000);
    private final SpotSymbol market = Mockito.spy(new SpotSymbol("BTCUSDT", BTC, USDT, 1_000_000L, 100_000L,
            100_000L, 1_000 * ONE, 10 * ONE, 0));
    // Half the leader's USDT bought 5 at 100.
    private final CopyLeadOrder lead = new CopyLeadOrder(Ulid.randomUuid(), leaderId, 10L, Side.BUY,
            CopyLeadOrder.WHOLE / 2, 500 * ONE, 5 * ONE);

    @BeforeEach
    void setUp() {
        lead.addFill(1, 5 * ONE, 500 * ONE);
        Mockito.doReturn(10L).when(market).getId();
        Mockito.when(markets.findById(10L)).thenReturn(Optional.of(market));
        Asset usdt = Mockito.mock(Asset.class);
        Mockito.when(usdt.getId()).thenReturn(USDT);
        Mockito.when(assets.getBySymbol("USDT")).thenReturn(usdt);
        CopySettings row = Mockito.mock(CopySettings.class);
        Mockito.when(row.getFailureLimit()).thenReturn(5);
        Mockito.when(settings.findById(CopySettings.SINGLETON_ID)).thenReturn(Optional.of(row));
        Mockito.when(follows.findByLeaderIdAndStatus(leaderId, FollowStatus.ACTIVE)).thenReturn(List.of(follow));
        Mockito.when(follows.findById(follow.getId())).thenReturn(Optional.of(follow));
        Mockito.when(follows.countFailure(follow.getId())).thenReturn(1);
        User account = Mockito.mock(User.class);
        Mockito.when(users.findById(ACCOUNT)).thenReturn(Optional.of(account));
        Mockito.when(eligibility.canTrade(account)).thenReturn(true);
        holds(USDT, 1_000 * ONE);
    }

    @Test
    @DisplayName("many followers: each gets one copy, several at once but never more than the limit, "
            + "and nobody is always in the first wave")
    @SuppressWarnings("unchecked")
    void manyFollowersInParallelAndShuffled() {
        int count = 40;
        List<CopyFollow> many = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            long account = 100 + i;
            CopyFollow f = new CopyFollow(Ulid.randomUuid(), leaderId, 1_000L + i, account, CopyMode.FIXED_RATIO, null, 1000);
            many.add(f);
            User user = Mockito.mock(User.class);
            Mockito.when(users.findById(account)).thenReturn(Optional.of(user));
            Mockito.when(eligibility.canTrade(user)).thenReturn(true);
            Balance balance = Mockito.mock(Balance.class);
            Mockito.when(balance.getAvailable()).thenReturn(1_000 * ONE);
            Mockito.when(balances.findByUserIdAndAssetId(account, USDT)).thenReturn(Optional.of(balance));
        }
        Mockito.when(follows.findByLeaderIdAndStatus(leaderId, FollowStatus.ACTIVE)).thenReturn(many);
        SpotOrder order = Mockito.mock(SpotOrder.class);
        Mockito.when(order.getId()).thenAnswer(call -> Ulid.randomUuid());

        java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger mostAtOnce = new java.util.concurrent.atomic.AtomicInteger();
        List<Long> started = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Mockito.when(orderService.placeCopyOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> {
                    started.add(call.getArgument(0));
                    mostAtOnce.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    Thread.sleep(5);
                    OrderDetail made = new OrderDetail(order, call.getArgument(2));
                    ((Consumer<OrderDetail>) call.getArgument(8)).accept(made);
                    inFlight.decrementAndGet();
                    return made;
                });

        // The first follower on the list lands in the first wave about one time in five when the
        // order is shuffled, and every time when it is not.
        boolean everOutOfFirstWave = false;
        for (int run = 0; run < 10; run++) {
            started.clear();
            service.copy(lead);
            assertThat(started).hasSize(count).doesNotHaveDuplicates();
            if (!started.subList(0, CopyService.PARALLELISM).contains(100L)) {
                everOutOfFirstWave = true;
            }
        }
        assertThat(everOutOfFirstWave).isTrue();
        assertThat(mostAtOnce.get()).isGreaterThan(1).isLessThanOrEqualTo(CopyService.PARALLELISM);
    }

    @Test
    @DisplayName("one copy that breaks does not stop the others, and still goes up to be tried again")
    void oneBrokenCopyDoesNotStopTheRest() {
        CopyFollow other = new CopyFollow(Ulid.randomUuid(), leaderId, 7L, 8L, CopyMode.FIXED_RATIO, null, 1000);
        User otherUser = Mockito.mock(User.class);
        Mockito.when(users.findById(8L)).thenReturn(Optional.of(otherUser));
        Mockito.when(eligibility.canTrade(otherUser)).thenReturn(true);
        Balance balance = Mockito.mock(Balance.class);
        Mockito.when(balance.getAvailable()).thenReturn(1_000 * ONE);
        Mockito.when(balances.findByUserIdAndAssetId(8L, USDT)).thenReturn(Optional.of(balance));
        Mockito.when(follows.findByLeaderIdAndStatus(leaderId, FollowStatus.ACTIVE)).thenReturn(List.of(follow, other));
        placing();
        Mockito.doThrow(new IllegalStateException("database is down")).when(orderService)
                .placeCopyOrder(eq(ACCOUNT), any(), any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.copy(lead)).isInstanceOf(IllegalStateException.class);
        Mockito.verify(orderService).placeCopyOrder(eq(8L), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a follower's copy is placed in their copy account, with its record written in the placing transaction")
    void placesTheCopy() {
        UUID orderId = placing();

        service.copy(lead);

        Mockito.verify(orderService).placeCopyOrder(eq(ACCOUNT), eq(FOLLOWER), eq("BTCUSDT"), eq(Side.BUY),
                eq(OrderType.LIMIT), eq(TimeInForce.IOC), eq("100.5"), eq("4.975"), any());
        CopyOrder copy = saved();
        assertThat(copy.getStatus()).isEqualTo(CopyOrderStatus.PLACED);
        assertThat(copy.getOrderId()).isEqualTo(orderId);
        assertThat(copy.getLeadOrderId()).isEqualTo(lead.getOrderId());
        assertThat(copy.getFollowId()).isEqualTo(follow.getId());
        Mockito.verify(follows, Mockito.never()).countFailure(any());
    }

    @Test
    @DisplayName("a buy is capped at the follower's own slippage cap")
    void followersOwnCap() {
        follow.setSlippageCapBps(30);
        placing();

        service.copy(lead);

        Mockito.verify(orderService).placeCopyOrder(any(), any(), any(), any(), any(), any(), eq("100.3"), any(), any());
    }

    @Test
    @DisplayName("a lead order already copied for a follow is not copied again")
    void copiesOnce() {
        Mockito.when(copyOrders.existsByLeadOrderIdAndFollowId(lead.getOrderId(), follow.getId())).thenReturn(true);

        service.copy(lead);

        Mockito.verifyNoInteractions(orderService);
        Mockito.verify(copyOrders, Mockito.never()).save(any());
    }

    @Test
    @DisplayName("losing the race to record a copy means it was copied: nothing is counted against the follower")
    void theSecondOfTwoIsNotAFailure() {
        Mockito.when(orderService.placeCopyOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("uq_copy_orders_lead_order_follow"));

        service.copy(lead);

        Mockito.verify(copyOrders, Mockito.never()).save(any());
        Mockito.verify(follows, Mockito.never()).countFailure(any());
    }

    @Test
    @DisplayName("a follower who may not trade, or has nothing to spend, has the copy recorded as failed and counted")
    void failuresBeforePlacing() {
        Mockito.when(eligibility.canTrade(any())).thenReturn(false);
        service.copy(lead);
        assertThat(saved().getReason()).isEqualTo(CopyOrderReason.NOT_ELIGIBLE);

        Mockito.reset(copyOrders);
        Mockito.when(eligibility.canTrade(any())).thenReturn(true);
        holds(USDT, 0);
        service.copy(lead);
        CopyOrder failed = saved();
        assertThat(failed.getStatus()).isEqualTo(CopyOrderStatus.FAILED);
        assertThat(failed.getReason()).isEqualTo(CopyOrderReason.INSUFFICIENT_BALANCE);
        assertThat(failed.getOrderId()).isNull();

        Mockito.verify(follows, Mockito.times(2)).countFailure(follow.getId());
        Mockito.verifyNoInteractions(orderService);
    }

    @Test
    @DisplayName("a market with no engine running is a skipped copy, not a failed one")
    void pausedMarketSkips() {
        Mockito.when(orderService.placeCopyOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new EngineUnavailableException());
        service.copy(lead);
        assertThat(saved().getStatus()).isEqualTo(CopyOrderStatus.SKIPPED);
        assertThat(saved().getReason()).isEqualTo(CopyOrderReason.MARKET_PAUSED);
        Mockito.verify(follows, Mockito.never()).countFailure(any());
    }

    @Test
    @DisplayName("an order the order path refuses is a failed copy; anything else goes up to be tried again")
    void refusedAndBroken() {
        Mockito.when(orderService.placeCopyOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new SymbolNotTradingException());
        service.copy(lead);
        assertThat(saved().getReason()).isEqualTo(CopyOrderReason.MARKET_CLOSED);
        Mockito.verify(follows).countFailure(follow.getId());

        Mockito.reset(copyOrders);
        // doThrow, not when: the call inside when() would run the stub above and throw.
        Mockito.doThrow(new IllegalStateException("database is down")).when(orderService)
                .placeCopyOrder(any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.copy(lead)).isInstanceOf(IllegalStateException.class);
        Mockito.verify(copyOrders, Mockito.never()).save(any());
    }

    @Test
    @DisplayName("a sell with nothing held is skipped: recorded so it is not looked at again, and not counted")
    void nothingToSellIsSkipped() {
        CopyLeadOrder sell = new CopyLeadOrder(Ulid.randomUuid(), leaderId, 10L, Side.SELL, CopyLeadOrder.WHOLE, ONE, ONE);
        sell.addFill(1, ONE, 100 * ONE);

        service.copy(sell);

        CopyOrder skipped = saved();
        assertThat(skipped.getStatus()).isEqualTo(CopyOrderStatus.SKIPPED);
        assertThat(skipped.getReason()).isEqualTo(CopyOrderReason.NOTHING_TO_SELL);
        Mockito.verify(follows, Mockito.never()).countFailure(any());
        Mockito.verify(orderService, Mockito.never()).placeCopyOrder(any(), any(), any(), any(), any(), isNull(),
                any(), any(), any());
    }

    @Test
    @DisplayName("the failure that reaches the limit closes the account; one short of it does not")
    void closesAtTheLimit() {
        Mockito.when(eligibility.canTrade(any())).thenReturn(false);
        follow.setFailuresInRow(4);
        service.copy(lead);
        Mockito.verifyNoInteractions(closes);

        Mockito.reset(copyOrders);
        follow.setFailuresInRow(5);
        service.copy(lead);
        Mockito.verify(closes).begin(follow.getId(), CloseReason.FAILURES);
    }

    @Test
    @DisplayName("a copy that traded is filled and the count starts again; the same trade twice changes nothing more")
    void filled() {
        CopyOrder copy = copy(lead.getOrderId());

        service.filled(copy);
        service.filled(copy);

        assertThat(copy.getStatus()).isEqualTo(CopyOrderStatus.FILLED);
        Mockito.verify(follows, Mockito.times(1)).clearFailures(follow.getId());
    }

    @Test
    @DisplayName("a copy that closed unfilled is one failure, however often its cancel is delivered")
    void notFilled() {
        CopyOrder copy = copy(lead.getOrderId());

        service.notFilled(copy, CopyOrderReason.NOT_FILLED);
        service.notFilled(copy, CopyOrderReason.NOT_FILLED);

        assertThat(copy.getStatus()).isEqualTo(CopyOrderStatus.FAILED);
        assertThat(copy.getReason()).isEqualTo(CopyOrderReason.NOT_FILLED);
        Mockito.verify(follows, Mockito.times(1)).countFailure(follow.getId());
    }

    @Test
    @DisplayName("a copy that filled and was then cancelled for its remainder stays filled")
    void aCancelAfterAFillIsNotAFailure() {
        CopyOrder copy = copy(lead.getOrderId());
        service.filled(copy);

        service.notFilled(copy, CopyOrderReason.NOT_FILLED);

        assertThat(copy.getStatus()).isEqualTo(CopyOrderStatus.FILLED);
        Mockito.verify(follows, Mockito.never()).countFailure(any());
    }

    @Test
    @DisplayName("a closing sale that does not fill is recorded, and counts against nobody")
    void aClosingSaleIsNotCounted() {
        CopyOrder sale = copy(null);

        service.notFilled(sale, CopyOrderReason.NOT_FILLED);
        service.filled(copy(null));

        assertThat(sale.getStatus()).isEqualTo(CopyOrderStatus.FAILED);
        Mockito.verify(follows, Mockito.never()).countFailure(any());
        Mockito.verify(follows, Mockito.never()).clearFailures(any());
    }

    private CopyOrder copy(UUID leadOrderId) {
        return new CopyOrder(Ulid.randomUuid(), follow.getId(), leadOrderId, Ulid.randomUuid(), 10L, Side.BUY,
                CopyOrderStatus.PLACED, null);
    }

    private void holds(long assetId, long available) {
        Balance balance = Mockito.mock(Balance.class);
        Mockito.when(balance.getAvailable()).thenReturn(available);
        Mockito.when(balances.findByUserIdAndAssetId(ACCOUNT, assetId)).thenReturn(Optional.of(balance));
    }

    /** The order path, run as it runs: the record is written inside it. */
    @SuppressWarnings("unchecked")
    private UUID placing() {
        UUID orderId = Ulid.randomUuid();
        Mockito.when(orderService.placeCopyOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> {
                    SpotOrder order = Mockito.mock(SpotOrder.class);
                    Mockito.when(order.getId()).thenReturn(orderId);
                    OrderDetail made = new OrderDetail(order, call.getArgument(2));
                    ((Consumer<OrderDetail>) call.getArgument(8)).accept(made);
                    return made;
                });
        return orderId;
    }

    private CopyOrder saved() {
        ArgumentCaptor<CopyOrder> saved = ArgumentCaptor.forClass(CopyOrder.class);
        Mockito.verify(copyOrders).save(saved.capture());
        return saved.getValue();
    }
}
