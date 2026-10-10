package com.exchange.spot.symbol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

import com.exchange.spot.symbol.MarketListingService.Limits;
import com.exchange.spot.symbol.MarketListingService.NewMarket;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetRepository;
import com.exchange.wallet.asset.AssetType;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class MarketListingServiceTest {

    private static final long ONE = 100_000_000L;

    private final SpotSymbolRepository markets = Mockito.mock(SpotSymbolRepository.class);
    private final AssetRepository assets = Mockito.mock(AssetRepository.class);
    private final MarketListingService listing = new MarketListingService(markets, assets);

    @BeforeEach
    void setUp() {
        asset(1L, "SOL");
        asset(2L, "USDT");
        Mockito.when(markets.save(any())).thenAnswer(call -> call.getArgument(0));
        Mockito.when(markets.findByBaseAssetIdAndQuoteAssetId(any(), any())).thenReturn(Optional.empty());
        Mockito.when(markets.findBySymbol(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("a new market is named base+quote, keeps its partition, tick and step, and opens in PRE_TRADING")
    void lists() {
        SpotSymbol market = listing.list(new NewMarket(1L, 2L, 3, ONE / 100, ONE / 1000, limits(ONE / 1000)), 8);

        assertThat(market.getSymbol()).isEqualTo("SOLUSDT");
        assertThat(market.getCommandPartition()).isEqualTo(3);
        assertThat(market.getPriceTick()).isEqualTo(ONE / 100);
        assertThat(market.getQtyStep()).isEqualTo(ONE / 1000);
        assertThat(market.getStatus()).isEqualTo(SymbolStatus.PRE_TRADING);
        assertThat(market.getMaxOpenOrders()).isEqualTo(200);
    }

    @Test
    @DisplayName("refuses a partition the engine does not have, one asset twice, a pair already listed, and sizing no order could meet")
    void refuses() {
        Limits ok = limits(ONE / 1000);
        assertThatThrownBy(() -> listing.list(new NewMarket(1L, 2L, 8, ONE, ONE / 1000, ok), 8))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("partitions 0 to 7");
        assertThatThrownBy(() -> listing.list(new NewMarket(1L, 1L, 0, ONE, ONE / 1000, ok), 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> listing.list(new NewMarket(1L, 2L, 0, 0, ONE / 1000, ok), 8))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("price tick");
        // A minimum off the step: no order sized to the step could ever meet it.
        assertThatThrownBy(() -> listing.list(new NewMarket(1L, 2L, 0, ONE, ONE / 1000, limits(ONE / 1000 + 1)), 8))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("multiple");

        Mockito.when(markets.findByBaseAssetIdAndQuoteAssetId(1L, 2L)).thenReturn(Optional.of(Mockito.mock(SpotSymbol.class)));
        assertThatThrownBy(() -> listing.list(new NewMarket(1L, 2L, 0, ONE, ONE / 1000, ok), 8))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("staff open a new market, and move a live one between trading and cancel-only -- nothing else")
    void statuses() {
        SpotSymbol market = new SpotSymbol("SOLUSDT", 1L, 2L, ONE, ONE / 1000, ONE / 1000, ONE, ONE, 0);
        ReflectionTestUtils.setField(market, "id", 5L);
        Mockito.when(markets.findById(5L)).thenReturn(Optional.of(market));

        assertThatThrownBy(() -> listing.setStatus(5L, SymbolStatus.CANCEL_ONLY))
                .as("a market that never opened is opened, not wound down").isInstanceOf(IllegalArgumentException.class);
        assertThat(listing.setStatus(5L, SymbolStatus.TRADING).getStatus()).isEqualTo(SymbolStatus.TRADING);
        assertThatThrownBy(() -> listing.setStatus(5L, SymbolStatus.TRADING)).isInstanceOf(IllegalStateException.class);
        assertThat(listing.setStatus(5L, SymbolStatus.CANCEL_ONLY).getStatus()).isEqualTo(SymbolStatus.CANCEL_ONLY);
        assertThat(listing.setStatus(5L, SymbolStatus.TRADING).getStatus()).isEqualTo(SymbolStatus.TRADING);

        for (SymbolStatus notOffered : new SymbolStatus[] {SymbolStatus.HALTED, SymbolStatus.DELISTED,
                SymbolStatus.POST_TRADING, SymbolStatus.PRE_TRADING}) {
            assertThatThrownBy(() -> listing.setStatus(5L, notOffered)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> listing.setStatus(99L, SymbolStatus.TRADING)).isInstanceOf(MarketListingService.MarketNotFound.class);
    }

    @Test
    @DisplayName("limits are checked against the market's own step, which they cannot change")
    void limitsKeepTheStep() {
        SpotSymbol market = new SpotSymbol("SOLUSDT", 1L, 2L, ONE, ONE / 1000, ONE / 1000, ONE, ONE, 0);
        Mockito.when(markets.findById(5L)).thenReturn(Optional.of(market));

        listing.setLimits(5L, new Limits(ONE / 100, 10 * ONE, 5 * ONE, null, null, null, null, null, 500, 50, 10, true, true));
        assertThat(market.getMinQty()).isEqualTo(ONE / 100);
        assertThat(market.getPriceBandBps()).isEqualTo(500);
        assertThat(market.isStopOrdersEnabled()).isTrue();
        assertThat(market.getQtyStep()).isEqualTo(ONE / 1000);

        assertThatThrownBy(() -> listing.setLimits(5L, new Limits(ONE, ONE / 100, ONE, null, null, null, null, null,
                null, 50, 10, true, false))).hasMessageContaining("below the minimum");
    }

    private static Limits limits(long minQty) {
        return new Limits(minQty, 1000 * ONE, ONE, null, null, null, null, null, null, 200, 50, true, false);
    }

    private void asset(Long id, String symbol) {
        Asset asset = new Asset(symbol, symbol, AssetType.CRYPTO, 8);
        ReflectionTestUtils.setField(asset, "id", id);
        Mockito.when(assets.findById(id)).thenReturn(Optional.of(asset));
    }
}
