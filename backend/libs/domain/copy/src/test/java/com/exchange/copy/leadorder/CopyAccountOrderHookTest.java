package com.exchange.copy.leadorder;

import com.exchange.copy.leadorder.CopyAccountOrderHook;
import com.exchange.copy.settings.CopyQuoteAsset;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exchange.common.id.Ulid;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.leader.LeaderStatus;
import com.exchange.copy.leadorder.CopyLeadOrder;
import com.exchange.copy.leadorder.CopyLeadOrderRepository;
import com.exchange.spot.order.Side;
import com.exchange.spot.order.exception.ManualOrderNotAllowedException;
import com.exchange.spot.order.exception.MarketNotAllowedException;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.user.identity.SubPurpose;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.identity.UserType;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** What a lead order leaves behind, and what each kind of sub-account is refused. */
class CopyAccountOrderHookTest {

    private static final long ONE = 100_000_000L;
    private static final long ACCOUNT = 7L;
    private static final long USDT = 3L;
    private static final long BTC = 4L;
    private static final long ETH = 5L;

    private final UserRepository users = Mockito.mock(UserRepository.class);
    private final CopyLeaderRepository leaders = Mockito.mock(CopyLeaderRepository.class);
    private final CopyLeadOrderRepository leadOrders = Mockito.mock(CopyLeadOrderRepository.class);
    private final BalanceRepository balances = Mockito.mock(BalanceRepository.class);
    private final AssetService assets = Mockito.mock(AssetService.class);
    private final CopyAccountOrderHook hook = new CopyAccountOrderHook(users, leaders, leadOrders, balances,
            new CopyQuoteAsset(assets, "USDT"));

    private final SpotSymbol btcUsdt = market(10L, "BTCUSDT", BTC, USDT);
    private final SpotSymbol ethBtc = market(11L, "ETHBTC", ETH, BTC);
    private final UUID orderId = Ulid.randomUuid();
    private final CopyLeader leader = new CopyLeader(Ulid.randomUuid(), 1L, "lead", null);

    @Test
    @DisplayName("an ordinary sub-account's order passes untouched, at the cost of one read")
    void ordinarySub() {
        account(SubPurpose.STANDARD);

        hook.beforeLock(orderId, ACCOUNT, btcUsdt, Side.BUY, 500 * ONE, 5 * ONE);

        Mockito.verifyNoInteractions(leaders, leadOrders, balances, assets);
    }

    @Test
    @DisplayName("a copy account takes no order placed by hand, and leaves nothing behind")
    void copyAccountIsRefused() {
        account(SubPurpose.COPY);

        assertThatThrownBy(() -> hook.beforeLock(orderId, ACCOUNT, btcUsdt, Side.BUY, 500 * ONE, 5 * ONE))
                .isInstanceOf(ManualOrderNotAllowedException.class);
        Mockito.verifyNoInteractions(leadOrders);
    }

    @Test
    @DisplayName("a lead account is refused a market not quoted in the copy asset")
    void leadAccountOutsideTheCopyAsset() {
        lead(LeaderStatus.ACTIVE);

        assertThatThrownBy(() -> hook.beforeLock(orderId, ACCOUNT, ethBtc, Side.BUY, ONE, ONE))
                .isInstanceOf(MarketNotAllowedException.class).hasMessageContaining("USDT");
        Mockito.verifyNoInteractions(leadOrders);
    }

    @Test
    @DisplayName("a lead buy records its cost as a fraction of the available quote, not of what is locked")
    void buyFraction() {
        lead(LeaderStatus.ACTIVE);
        holds(USDT, 1_000 * ONE, 4_000 * ONE);

        hook.beforeLock(orderId, ACCOUNT, btcUsdt, Side.BUY, 500 * ONE, 5 * ONE);

        CopyLeadOrder recorded = recorded();
        assertThat(recorded.getFraction()).isEqualTo(CopyLeadOrder.WHOLE / 2);
        assertThat(recorded.getAmount()).as("what the fraction was a share of").isEqualTo(500 * ONE);
        assertThat(recorded.getQuantity()).as("what tells the copier the order has filled").isEqualTo(5 * ONE);
        assertThat(recorded.getOrderId()).isEqualTo(orderId);
        assertThat(recorded.getLeaderId()).isEqualTo(leader.getId());
        assertThat(recorded.getSymbolId()).isEqualTo(10L);
        assertThat(recorded.getSide()).isEqualTo(Side.BUY);
    }

    @Test
    @DisplayName("a lead sell records its quantity as a fraction of everything held, resting orders included")
    void sellFraction() {
        lead(LeaderStatus.ACTIVE);
        holds(BTC, ONE / 2, ONE / 2);

        hook.beforeLock(orderId, ACCOUNT, btcUsdt, Side.SELL, ONE / 5, ONE / 5);

        assertThat(recorded().getFraction()).isEqualTo(CopyLeadOrder.WHOLE / 5);
    }

    @Test
    @DisplayName("the fraction stays inside what the table takes: never above all of it, never zero")
    void fractionBounds() {
        assertThat(CopyAccountOrderHook.fraction(600 * ONE, 500 * ONE)).isEqualTo(CopyLeadOrder.WHOLE);
        assertThat(CopyAccountOrderHook.fraction(1, 1_000_000 * ONE)).isEqualTo(1);
        assertThat(CopyAccountOrderHook.fraction(250 * ONE, 1_000 * ONE)).isEqualTo(CopyLeadOrder.WHOLE / 4);
    }

    @Test
    @DisplayName("a suspended leader's order is its own: placed as usual, recorded for nobody")
    void suspendedLeader() {
        lead(LeaderStatus.SUSPENDED);
        holds(USDT, 1_000 * ONE, 0);

        hook.beforeLock(orderId, ACCOUNT, btcUsdt, Side.BUY, 500 * ONE, 5 * ONE);

        Mockito.verifyNoInteractions(leadOrders);
    }

    @Test
    @DisplayName("with nothing to draw on there is no fraction; the lock that follows refuses the order")
    void nothingHeld() {
        lead(LeaderStatus.ACTIVE);
        Mockito.when(balances.findByUserIdAndAssetId(ACCOUNT, USDT)).thenReturn(Optional.empty());

        hook.beforeLock(orderId, ACCOUNT, btcUsdt, Side.BUY, 500 * ONE, 5 * ONE);

        Mockito.verifyNoInteractions(leadOrders);
    }

    private void account(SubPurpose purpose) {
        User account = new User("s_virtual@x.com", null);
        account.setType(UserType.SUB);
        account.setSubPurpose(purpose);
        Mockito.when(users.findById(ACCOUNT)).thenReturn(Optional.of(account));
    }

    private void lead(LeaderStatus status) {
        account(SubPurpose.LEAD);
        leader.setStatus(status);
        Mockito.when(leaders.findByLeadUserId(ACCOUNT)).thenReturn(Optional.of(leader));
        Asset usdt = Mockito.mock(Asset.class);
        Mockito.when(usdt.getId()).thenReturn(USDT);
        Mockito.when(assets.getBySymbol("USDT")).thenReturn(usdt);
    }

    private void holds(long assetId, long available, long locked) {
        Balance balance = Mockito.mock(Balance.class);
        Mockito.when(balance.getAvailable()).thenReturn(available);
        Mockito.when(balance.getTotal()).thenReturn(available + locked);
        Mockito.when(balances.findByUserIdAndAssetId(ACCOUNT, assetId)).thenReturn(Optional.of(balance));
    }

    private CopyLeadOrder recorded() {
        ArgumentCaptor<CopyLeadOrder> saved = ArgumentCaptor.forClass(CopyLeadOrder.class);
        Mockito.verify(leadOrders).save(saved.capture());
        return saved.getValue();
    }

    private static SpotSymbol market(long id, String symbol, long base, long quote) {
        SpotSymbol market = Mockito.spy(new SpotSymbol(symbol, base, quote, 1L, 1L, 1L, Long.MAX_VALUE / 2, 1L, 0));
        Mockito.doReturn(id).when(market).getId();
        return market;
    }
}
