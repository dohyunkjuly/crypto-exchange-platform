package com.exchange.spot.openapi.markets;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

/**
 * The public market endpoints against the running stack: the listing with its bounds and limits,
 * candles paging by {@code endTime}, the grouped book, and this app's own error codes for a
 * bad symbol, interval or grouping. Skipped, not failed, without {@code docker compose up -d}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA="})
class MarketsIT {

    @BeforeAll
    static void requireInfrastructure() {
        requirePort("mysql", 3308);
        requirePort("mongo", 27018);
        requirePort("redis", 6379);
        requirePort("kafka", 9092);
    }

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;

    private JsonNode get(String path) {
        ResponseEntity<JsonNode> r = rest.getForEntity("http://localhost:" + port + path, JsonNode.class);
        return r.getBody();
    }

    @Test
    @DisplayName("info lists every market with its bounds, and the limits this API enforces")
    void info() {
        JsonNode data = get("/v1/markets/info").get("data");
        List<String> symbols = new ArrayList<>();
        data.get("symbols").forEach(s -> symbols.add(s.get("symbol").asText()));
        assertThat(symbols).contains("BTCUSDT", "ETHUSDT");
        JsonNode btc = data.get("symbols").get(symbols.indexOf("BTCUSDT"));
        assertThat(btc.get("priceTick").asText()).isEqualTo("0.01");
        assertThat(btc.get("marketMaxQty").asText()).isEqualTo("100"); // db/seed
        assertThat(btc.get("depthGroupings").isArray()).isTrue();
        assertThat(data.get("rateLimits")).hasSize(3);
        assertThat(data.get("rateLimits").get(0).get("type").asText()).isEqualTo("REQUEST_WEIGHT");
    }

    @Test
    @DisplayName("candles page back: the page ending just before the newest page's first bar joins it without a gap")
    void candlesPageBack() {
        JsonNode newest = get("/v1/markets/BTCUSDT/candles?interval=1m&limit=5").get("data");
        Assumptions.assumeTrue(newest.size() == 5, "BTCUSDT has fewer than five 1m candles; nothing to page");
        Instant first = Instant.parse(newest.get(0).get("openTime").asText());
        JsonNode older = get("/v1/markets/BTCUSDT/candles?interval=1m&limit=5&endTime=" + first.minusMillis(1)).get("data");
        assertThat(older).isNotEmpty();
        assertThat(Instant.parse(older.get(older.size() - 1).get("openTime").asText())).isEqualTo(first.minusSeconds(60));
        assertThat(newest.get(0).get("open").asText()).matches("\\d+(\\.\\d+)?");
    }

    @Test
    @DisplayName("every response reports the running weight, and a deep book costs more than a shallow one")
    void usedWeightHeader() {
        ResponseEntity<JsonNode> shallow = rest.getForEntity("http://localhost:" + port + "/v1/markets/BTCUSDT/depth?limit=20", JsonNode.class);
        ResponseEntity<JsonNode> deep = rest.getForEntity("http://localhost:" + port + "/v1/markets/BTCUSDT/depth?limit=5000", JsonNode.class);
        long afterShallow = Long.parseLong(shallow.getHeaders().getFirst("X-USED-WEIGHT-1M"));
        long afterDeep = Long.parseLong(deep.getHeaders().getFirst("X-USED-WEIGHT-1M"));
        assertThat(afterDeep - afterShallow).isEqualTo(50);
    }

    @Test
    void depthRawAndGrouped() {
        JsonNode raw = get("/v1/markets/BTCUSDT/depth?limit=20").get("data");
        assertThat(raw.get("symbol").asText()).isEqualTo("BTCUSDT");
        assertThat(raw.get("bids").isArray()).isTrue();
        JsonNode grouped = get("/v1/markets/BTCUSDT/depth?limit=20&grouping=0.1").get("data");
        assertThat(grouped.get("asks").isArray()).isTrue();
        assertThat(get("/v1/markets/BTCUSDT/trades?limit=3").get("data").isArray()).isTrue();
        assertThat(get("/v1/markets/tickers").get("data").isArray()).isTrue();
    }

    @Test
    @DisplayName("a mistyped symbol is refused with 5001 rather than answered with an empty book")
    void errorsCarryThisAppsCodes() {
        assertThat(get("/v1/markets/NOPEUSDT/depth").get("code").asInt()).isEqualTo(5001);
        assertThat(get("/v1/markets/BTCUSDT/candles?interval=7m").get("code").asInt()).isEqualTo(6001);
        assertThat(get("/v1/markets/BTCUSDT/depth?grouping=-1").get("code").asInt()).isEqualTo(6003);
    }

    private static void requirePort(String service, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
        } catch (Exception e) {
            Assumptions.abort(service + " not reachable -- run `docker compose up -d`");
        }
    }
}
