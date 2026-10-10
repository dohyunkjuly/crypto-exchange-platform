package com.exchange.copy.follow;

import com.exchange.copy.follow.CopyCloseService;
import com.exchange.copy.settings.CopyQuoteAsset;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

import com.exchange.common.id.Ulid;
import com.exchange.copy.share.CopyProfitShareService;
import com.exchange.copy.share.ShareTrigger;
import com.exchange.copy.leader.exception.LeadAccountNotFlatException;
import com.exchange.copy.follow.CloseReason;
import com.exchange.copy.follow.CopyFollow;
import com.exchange.copy.follow.CopyFollowRepository;
import com.exchange.copy.follow.CopyMode;
import com.exchange.copy.follow.FollowStatus;
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
import com.exchange.spot.order.SpotOrderRepository;
import com.exchange.spot.order.dto.OrderDetail;
import com.exchange.spot.order.exception.SymbolNotTradingException;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.spot.trade.SpotTrade;
import com.exchange.spot.trade.SpotTradeRepository;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceRepository;
import com.exchange.wallet.transfer.TransferService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** A copy account is sold, then emptied -- and emptied only once it has settled. */
class CopyCloseServiceTest {

    private static final long ONE = 100_000_000L;
    private static final long FOLLOWER = 1L;
    private static final long ACCOUNT = 2L;
    private static final long USDT = 3L;
    private static final long BTC = 4L;
    private static final long DUST = 5L;

    private final CopyFollowRepository follows = Mockito.mock(CopyFollowRepository.class);
    private final CopyOrderRepository copyOrders = Mockito.mock(CopyOrderRepository.class);
    private final CopySettingsRepository settings = Mockito.mock(CopySettingsRepository.class);
    private final BalanceRepository balances = Mockito.mock(BalanceRepository.class);
    private final SpotSymbolRepository markets = Mockito.mock(SpotSymbolRepository.class);
    private final SpotTradeRepository trades = Mockito.mock(SpotTradeRepository.class);
    private final SpotOrderRepository orders = Mockito.mock(SpotOrderRepository.class);
    private final OrderService orderService = Mockito.mock(OrderService.class);
    private final TransferService transfers = Mockito.mock(TransferService.class);
    private final AssetService assets = Mockito.mock(AssetService.class);
    private final CopyProfitShareService shares = Mockito.mock(CopyProfitShareService.class);
    private final CopyCloseService service = new CopyCloseService(follows, copyOrders, settings, balances, markets,
            trades, orders, orderService, transfers, new CopyQuoteAsset(assets, "USDT"), shares);

    private final UUID followId = Ulid.randomUuid();
    private final CopyFollow follow = new CopyFollow(followId, Ulid.randomUuid(), FOLLOWER, ACCOUNT,
            CopyMode.FIXED_RATIO, null, 1000);
    // Step 0.001, minimum 0.001.
    private final SpotSymbol btcUsdt = market(10L, "BTCUSDT", BTC);
    private final SpotSymbol dustUsdt = market(11L, "DUSTUSDT", DUST);

    @BeforeEach
    void setUp() {
        Asset usdt = Mockito.mock(Asset.class);
        Mockito.when(usdt.getId()).thenReturn(USDT);
        Mockito.when(assets.getBySymbol("USDT")).thenReturn(usdt);
        CopySettings row = Mockito.mock(CopySettings.class);
        Mockito.when(row.getCloseSellThreshold()).thenReturn(10 * ONE);
        Mockito.when(settings.findById(CopySettings.SINGLETON_ID)).thenReturn(Optional.of(row));
        Mockito.when(follows.findById(followId)).thenReturn(Optional.of(follow));
        Mockito.when(markets.findByBaseAssetIdAndQuoteAssetId(BTC, USDT)).thenReturn(Optional.of(btcUsdt));
        Mockito.when(markets.findByBaseAssetIdAndQuoteAssetId(DUST, USDT)).thenReturn(Optional.of(dustUsdt));
        price(btcUsdt, 60_000 * ONE);
        price(dustUsdt, ONE);
    }

    @Test
    @DisplayName("closing sells each holding at market on the market's step, and leaves USDT and dust alone")
    void sellsWhatIsWorthSelling() {
        Mockito.when(follows.startClosing(followId, CloseReason.FAILURES)).thenReturn(1);
        // 0.12345 BTC goes as 0.123; 3 DUST is worth 3 USDT, under the 10 threshold.
        holds(balance(USDT, 400 * ONE, 0), balance(BTC, 12_345_000L, 0), balance(DUST, 3 * ONE, 0));
        placing(Ulid.randomUuid());

        assertThat(service.begin(followId, CloseReason.FAILURES)).isTrue();

        Mockito.verify(orderService).placeCopyOrder(eq(ACCOUNT), eq(FOLLOWER), eq("BTCUSDT"), eq(Side.SELL),
                eq(OrderType.MARKET), isNull(), isNull(), eq("0.123"), any());
        Mockito.verifyNoMoreInteractions(orderService);
        CopyOrder sale = saved();
        assertThat(sale.getLeadOrderId()).as("a closing sale copies no lead order").isNull();
        assertThat(sale.getStatus()).isEqualTo(CopyOrderStatus.PLACED);
        assertThat(sale.getSide()).isEqualTo(Side.SELL);
    }

    @Test
    @DisplayName("a lead account is flat with USDT and dust only; an open order or a coin worth selling says not yet")
    void flatMeansNothingWorthSelling() {
        holds(balance(USDT, 400 * ONE, 0), balance(DUST, 3 * ONE, 0));
        Mockito.when(orders.countByUserIdAndStatusIn(eq(ACCOUNT), any())).thenReturn(0L);
        service.requireFlat(ACCOUNT);

        holds(balance(USDT, 400 * ONE, 0), balance(BTC, 12_345_000L, 0));
        assertThatThrownBy(() -> service.requireFlat(ACCOUNT)).isInstanceOf(LeadAccountNotFlatException.class);

        holds(balance(USDT, 400 * ONE, 0));
        Mockito.when(orders.countByUserIdAndStatusIn(eq(ACCOUNT), any())).thenReturn(1L);
        assertThatThrownBy(() -> service.requireFlat(ACCOUNT)).isInstanceOf(LeadAccountNotFlatException.class);
    }

    @Test
    @DisplayName("only the caller that moves the account out of ACTIVE sells: a second close does nothing")
    void closesOnce() {
        Mockito.when(follows.startClosing(followId, CloseReason.STOPPED)).thenReturn(0);

        assertThat(service.begin(followId, CloseReason.STOPPED)).isFalse();

        Mockito.verifyNoInteractions(orderService, balances);
    }

    @Test
    @DisplayName("a market that refuses the sale is recorded and the holding is left to be returned as it is")
    void aRefusedSaleIsRecorded() {
        Mockito.when(follows.startClosing(followId, CloseReason.STOPPED)).thenReturn(1);
        holds(balance(BTC, ONE, 0));
        Mockito.when(orderService.placeCopyOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new SymbolNotTradingException());

        service.begin(followId, CloseReason.STOPPED);

        CopyOrder sale = saved();
        assertThat(sale.getStatus()).isEqualTo(CopyOrderStatus.FAILED);
        assertThat(sale.getReason()).isEqualTo(CopyOrderReason.MARKET_CLOSED);
        assertThat(sale.getOrderId()).isNull();
    }

    @Test
    @DisplayName("nothing is returned while a sale is open or funds are locked: the proceeds have not arrived")
    void waitsUntilSettled() {
        follow.setStatus(FollowStatus.CLOSING);
        holds(balance(USDT, 400 * ONE, 0), balance(BTC, 0, ONE));
        Mockito.when(orders.countByUserIdAndStatusIn(eq(ACCOUNT), any())).thenReturn(1L);
        assertThat(service.finishIfSettled(followId)).isFalse();

        Mockito.when(orders.countByUserIdAndStatusIn(eq(ACCOUNT), any())).thenReturn(0L);
        assertThat(service.finishIfSettled(followId)).as("still locked").isFalse();

        Mockito.verifyNoInteractions(transfers);
        assertThat(follow.getStatus()).isEqualTo(FollowStatus.CLOSING);
    }

    @Test
    @DisplayName("once settled, every asset goes back to the follower and the account is closed")
    void returnsEverything() {
        follow.setStatus(FollowStatus.CLOSING);
        holds(balance(USDT, 7_780 * ONE, 0), balance(BTC, 0, 0), balance(DUST, 3 * ONE, 0));
        Mockito.when(orders.countByUserIdAndStatusIn(eq(ACCOUNT), any())).thenReturn(0L);

        assertThat(service.finishIfSettled(followId)).isTrue();

        Mockito.verify(transfers).transfer(any(), eq(ACCOUNT), eq(FOLLOWER), eq(USDT), eq(7_780 * ONE), eq(FOLLOWER));
        Mockito.verify(transfers).transfer(any(), eq(ACCOUNT), eq(FOLLOWER), eq(DUST), eq(3 * ONE), eq(FOLLOWER));
        Mockito.verify(transfers, Mockito.never()).transfer(any(), any(), any(), eq(BTC), anyLong(), any());
        assertThat(follow.getStatus()).isEqualTo(FollowStatus.CLOSED);
        assertThat(follow.getClosedAt()).isNotNull();
        // The leader's share of the last profit leaves before the rest goes back, in this transaction.
        Mockito.inOrder(shares, transfers).verify(shares).settle(followId, ShareTrigger.CLOSE, null);
        Mockito.inOrder(shares, transfers).verify(transfers).transfer(any(), eq(ACCOUNT), eq(FOLLOWER), eq(USDT), anyLong(), any());
    }

    @Test
    @DisplayName("an account that is not closing is left alone")
    void onlyAClosingAccount() {
        assertThat(service.finishIfSettled(followId)).isFalse();
        Mockito.verifyNoInteractions(transfers, balances);
    }

    private void holds(Balance... held) {
        Mockito.when(balances.findByUserId(ACCOUNT)).thenReturn(List.of(held));
    }

    private static Balance balance(long assetId, long available, long locked) {
        Balance balance = Mockito.mock(Balance.class);
        Mockito.when(balance.getAssetId()).thenReturn(assetId);
        Mockito.when(balance.getAvailable()).thenReturn(available);
        Mockito.when(balance.getLocked()).thenReturn(locked);
        Mockito.when(balance.getTotal()).thenReturn(available + locked);
        return balance;
    }

    private void price(SpotSymbol market, long price) {
        SpotTrade last = Mockito.mock(SpotTrade.class);
        Mockito.when(last.getPrice()).thenReturn(price);
        Mockito.when(trades.lastBefore(eq(market.getId()), any())).thenReturn(Optional.of(last));
    }

    /** The order path, run as it runs: the record is written inside it. */
    @SuppressWarnings("unchecked")
    private void placing(UUID orderId) {
        Mockito.when(orderService.placeCopyOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> {
                    SpotOrder order = Mockito.mock(SpotOrder.class);
                    Mockito.when(order.getId()).thenReturn(orderId);
                    OrderDetail made = new OrderDetail(order, call.getArgument(2));
                    ((Consumer<OrderDetail>) call.getArgument(8)).accept(made);
                    return made;
                });
    }

    private CopyOrder saved() {
        ArgumentCaptor<CopyOrder> saved = ArgumentCaptor.forClass(CopyOrder.class);
        Mockito.verify(copyOrders).save(saved.capture());
        return saved.getValue();
    }

    private static SpotSymbol market(long id, String symbol, long base) {
        SpotSymbol market = Mockito.spy(new SpotSymbol(symbol, base, USDT, 1L, 100_000L, 100_000L,
                Long.MAX_VALUE / 2, 1L, 0));
        Mockito.doReturn(id).when(market).getId();
        return market;
    }
}
