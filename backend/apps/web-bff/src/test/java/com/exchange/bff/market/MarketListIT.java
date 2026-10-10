package com.exchange.bff.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.bff.ticker.TickerCache;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * {@code /bff/symbols} and {@code /bff/market/tickers} -- the two halves a trading screen
 * is built from: the rules of each market, and what it has been doing.
 *
 * <p>Asserts against the seed (db/seed) inserts rather than rows this test
 * inserts. That is deliberate: those markets are the product's own configuration, so an
 * assertion on BTCUSDT's tick size is an assertion that real listings render correctly, not
 * that a fixture round-trips. It also means this test notices if the seed ever changes
 * them by accident.
 *
 * <p>Skipped, not failed, when the infrastructure is not running.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                // Every HTTP integration test raises this, and must: the limiter counts per
                // client IP in Redis, which every test in a run shares. Left at the default
                // an early suite exhausts the window and every later one gets 429s whose
                // envelope carries a null `data` -- so assertions fail as "missing field"
                // rather than "rate limited", pointing at the wrong bug entirely.
                "exchange.rate-limit.max-requests=100000"})
class MarketListIT {

    @BeforeAll
    static void requireInfrastructure() {
        requirePort("mysql", 3308);
        requirePort("redis", 6379);
        requirePort("mongodb", 27018);
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private TickerCache cache;

    /** The scheduler fires its first run at startup, but this test must not race it. */
    @BeforeEach
    void ensureTheSnapshotIsBuilt() {
        cache.refresh();
    }

    @Test
    @DisplayName("every seeded market is listed with its assets resolved to codes")
    void symbolsCarryTheOrderFormsRules() {
        JsonNode btc = symbolNamed("BTCUSDT");

        assertThat(btc.get("baseAsset").asText()).isEqualTo("BTC");
        assertThat(btc.get("quoteAsset").asText()).isEqualTo("USDT");
        assertThat(btc.get("status").asText()).isEqualTo("TRADING");

        // The seed's own numbers: a 0.01 USDT tick, a 0.00001 BTC step, a 10 USDT minimum.
        assertThat(btc.get("priceTick").asText()).isEqualTo("0.01");
        assertThat(btc.get("qtyStep").asText()).isEqualTo("0.00001");
        assertThat(btc.get("minNotional").asText()).isEqualTo("10");

        assertThat(btc.get("marketOrdersEnabled").asBoolean()).isTrue();
    }

    /**
     * The grouping selector is populated by the server. A client cannot derive these: the
     * useful coarse options depend on price as well as tick, and BTC's tick is around 400
     * times finer relative to its price than DOGE's -- the same multiplier ladder cannot
     * suit both.
     */
    @Test
    void symbolsCarryTheOrderBookGroupingOptions() {
        JsonNode btc = symbolNamed("BTCUSDT");
        JsonNode doge = symbolNamed("DOGEUSDT");

        // The seed's own values, as decimals rather than the scaled longs it stores.
        assertThat(namesOfField(btc.get("depthGroupings")))
                .containsExactly("0.01", "0.1", "1", "10", "50", "100");
        assertThat(namesOfField(doge.get("depthGroupings")))
                .containsExactly("0.00001", "0.0001", "0.001", "0.01");
    }

    /** Every option must be a whole multiple of the market's own tick, or bucket
     *  boundaries fall mid-tick and the same price groups differently depending on where
     *  the ladder starts. */
    @Test
    void everyGroupingIsAWholeMultipleOfThatMarketsTick() {
        for (JsonNode market : get("/bff/symbols")) {
            BigDecimal tick = new BigDecimal(market.get("priceTick").asText());
            for (JsonNode grouping : market.get("depthGroupings")) {
                BigDecimal value = new BigDecimal(grouping.asText());
                assertThat(value).as("%s grouping %s", market.get("symbol").asText(), value)
                        .isGreaterThanOrEqualTo(tick);
                assertThat(value.remainder(tick))
                        .as("%s grouping %s is not a multiple of tick %s",
                                market.get("symbol").asText(), value, tick)
                        .isEqualByComparingTo(BigDecimal.ZERO);
            }
        }
    }

    /** Money as decimal strings, not JSON numbers -- a tick size the browser reads as a
     *  double is how an order form starts rounding prices wrong. */
    @Test
    void symbolLimitsAreStringsNotNumbers() {
        JsonNode btc = symbolNamed("BTCUSDT");

        assertThat(btc.get("priceTick").isTextual()).isTrue();
        assertThat(btc.get("qtyStep").isTextual()).isTrue();
        assertThat(btc.get("minNotional").isTextual()).isTrue();
    }

    /** Reference data and prices are separate calls: a client fetches the rules once and
     *  polls only the numbers that move. */
    @Test
    void tickersCarryNoReferenceData() {
        JsonNode ticker = get("/bff/market/tickers").get(0);

        assertThat(ticker.has("priceTick")).isFalse();
        assertThat(ticker.has("minNotional")).isFalse();
        assertThat(ticker.has("lastPrice")).isTrue();
        assertThat(ticker.has("priceChangePercent")).isTrue();
        assertThat(ticker.has("quoteVolume")).isTrue();
    }

    /**
     * A listed market with no trades still appears, reporting zeros. Omitting it would look
     * to a user exactly like a market that does not exist -- and a newly listed pair is the
     * one people go looking for.
     */
    @Test
    void everyVisibleMarketHasATickerEvenWithNoHistory() {
        List<String> listed = namesOf(get("/bff/symbols"));
        List<String> quoted = namesOf(get("/bff/market/tickers"));

        assertThat(listed).contains("BTCUSDT").hasSizeGreaterThan(1);
        assertThat(quoted).containsExactlyInAnyOrderElementsOf(listed);
    }

    /**
     * The point of the cache. Without it a markets list costs one candle range query per
     * symbol per poll per browser, so database load grows with the audience -- and a
     * markets list is polled by every open tab, forever.
     */
    @Test
    void repeatedReadsServeTheSameComputedSnapshot() {
        assertThat(cache.all()).isSameAs(cache.all());
    }

    // --- helpers ---

    private JsonNode get(String path) {
        return http.getForObject(path, JsonNode.class).get("data");
    }

    private JsonNode symbolNamed(String symbol) {
        for (JsonNode node : get("/bff/symbols")) {
            if (symbol.equals(node.get("symbol").asText())) {
                return node;
            }
        }
        throw new AssertionError(symbol + " is not listed -- reference data missing?");
    }

    private static List<String> namesOfField(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static List<String> namesOf(JsonNode array) {
        List<String> names = new ArrayList<>();
        array.forEach(node -> names.add(node.get("symbol").asText()));
        return names;
    }

    private static void requirePort(String service, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
        } catch (Exception e) {
            Assumptions.abort(service + " not reachable -- run `docker compose up -d`");
        }
    }
}
