package com.exchange.spot.settlement.fee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.exchange.fee.FeeProduct;
import com.exchange.fee.volume.UserDailyVolume;
import com.exchange.fee.volume.UserDailyVolumeRepository;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.spot.trade.SpotTrade;
import com.exchange.spot.trade.SpotTradeRepository;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.asset.AssetType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * A day's trades become one row per account: both sides count, a foreign quote is converted at
 * the day's close, an unpriceable one is left out, and the day is wiped before it is rewritten.
 */
class VolumeRecountServiceTest {

    private static final long ONE = 100_000_000L;
    private static final long USDT = 1L;
    private static final long BTC = 2L;
    private static final long ETH = 3L;
    private static final long XYZ = 4L;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);
    private static final Instant FROM = Instant.parse("2026-09-26T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-27T00:00:00Z");

    private final SpotTradeRepository trades = Mockito.mock(SpotTradeRepository.class);
    private final SpotSymbolRepository markets = Mockito.mock(SpotSymbolRepository.class);
    private final AssetService assets = Mockito.mock(AssetService.class);
    private final UserDailyVolumeRepository volumes = Mockito.mock(UserDailyVolumeRepository.class);
    private final VolumeRecountService service = new VolumeRecountService(trades, markets, assets, volumes, "USDT");

    private final SpotSymbol btcUsdt = market(10L, "BTCUSDT", BTC, USDT);
    private final SpotSymbol ethBtc = market(11L, "ETHBTC", ETH, BTC);
    private final SpotSymbol xyzEth = market(12L, "XYZETH", XYZ, ETH);

    @Test
    @DisplayName("buyers and sellers both count, a BTC-quoted market converts at the day's BTC close, and an unpriceable one is skipped")
    void recounts() {
        Asset usdt = Mockito.spy(new Asset("USDT", "Tether", AssetType.CRYPTO, 2));
        Mockito.doReturn(USDT).when(usdt).getId();
        Mockito.when(assets.getBySymbol("USDT")).thenReturn(usdt);
        Mockito.when(markets.findAll()).thenReturn(List.of(btcUsdt, ethBtc, xyzEth));
        Mockito.when(markets.findByBaseAssetIdAndQuoteAssetId(BTC, USDT)).thenReturn(Optional.of(btcUsdt));
        Mockito.when(markets.findByBaseAssetIdAndQuoteAssetId(ETH, USDT)).thenReturn(Optional.empty());

        // BTCUSDT: user 1 bought 1,000 USDT worth in two trades, user 2 sold it.
        Mockito.when(trades.buyerVolumeBetween(10L, FROM, TO)).thenReturn(rows(row(1L, 1_000 * ONE, 2)));
        Mockito.when(trades.sellerVolumeBetween(10L, FROM, TO)).thenReturn(rows(row(2L, 1_000 * ONE, 2)));
        // ETHBTC: user 1 sold 0.5 BTC worth; BTC closed the day at 60,000.
        Mockito.when(trades.buyerVolumeBetween(11L, FROM, TO)).thenReturn(rows(row(3L, ONE / 2, 1)));
        Mockito.when(trades.sellerVolumeBetween(11L, FROM, TO)).thenReturn(rows(row(1L, ONE / 2, 1)));
        SpotTrade close = Mockito.mock(SpotTrade.class);
        Mockito.when(close.getPrice()).thenReturn(60_000 * ONE);
        Mockito.when(trades.lastBefore(10L, TO)).thenReturn(Optional.of(close));
        // XYZETH: traded, but ETH has no USDT market, so nobody's volume moves.
        Mockito.when(trades.buyerVolumeBetween(12L, FROM, TO)).thenReturn(rows(row(1L, 5 * ONE, 1)));
        Mockito.when(trades.sellerVolumeBetween(12L, FROM, TO)).thenReturn(rows(row(4L, 5 * ONE, 1)));

        int accounts = service.recount(DAY);

        assertThat(accounts).isEqualTo(3);
        InOrder order = Mockito.inOrder(volumes);
        order.verify(volumes).deleteByTradeDateAndProduct(DAY, FeeProduct.SPOT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UserDailyVolume>> saved = ArgumentCaptor.forClass(Collection.class);
        order.verify(volumes).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(UserDailyVolume::getUserId, UserDailyVolume::getVolume,
                UserDailyVolume::getTradeCount, UserDailyVolume::getTradeDate, UserDailyVolume::getProduct)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1L, 31_000 * ONE, 3L, DAY, FeeProduct.SPOT),
                        org.assertj.core.groups.Tuple.tuple(2L, 1_000 * ONE, 2L, DAY, FeeProduct.SPOT),
                        org.assertj.core.groups.Tuple.tuple(3L, 30_000 * ONE, 1L, DAY, FeeProduct.SPOT));
    }

    @Test
    @DisplayName("a day nobody traded is still wiped, so a recount never leaves stale rows behind")
    void quietDay() {
        Asset usdt = Mockito.spy(new Asset("USDT", "Tether", AssetType.CRYPTO, 2));
        Mockito.doReturn(USDT).when(usdt).getId();
        Mockito.when(assets.getBySymbol("USDT")).thenReturn(usdt);
        Mockito.when(markets.findAll()).thenReturn(List.of(btcUsdt));
        Mockito.when(trades.buyerVolumeBetween(eq(10L), any(), any())).thenReturn(List.of());
        Mockito.when(trades.sellerVolumeBetween(eq(10L), any(), any())).thenReturn(List.of());

        assertThat(service.recount(DAY)).isZero();
        Mockito.verify(volumes).deleteByTradeDateAndProduct(DAY, FeeProduct.SPOT);
    }

    private static SpotSymbol market(long id, String symbol, long base, long quote) {
        SpotSymbol market = Mockito.spy(new SpotSymbol(symbol, base, quote, 1L, 1L, 1L, Long.MAX_VALUE / 2, 1L, 0));
        Mockito.doReturn(id).when(market).getId();
        return market;
    }

    private static Object[] row(long userId, long volume, long count) {
        return new Object[] {userId, volume, count};
    }

    private static List<Object[]> rows(Object[]... rows) {
        return List.of(rows);
    }

    @SuppressWarnings("unused")
    private static UUID id() {
        return UUID.randomUUID();
    }
}
