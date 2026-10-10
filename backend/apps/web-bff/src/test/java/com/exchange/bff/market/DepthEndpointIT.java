package com.exchange.bff.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.depth.DepthBook;
import com.exchange.marketdata.tape.TradeTape;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * {@code GET /bff/market/{symbol}/depth} against a real MongoDB.
 *
 * <p>The book is inserted as a raw BSON document rather than through any of this app's
 * code, deliberately: the point is to prove this app reads the shape {@code market-data}
 * actually writes, not the shape its own objects happen to serialise to. If the two ever
 * disagree -- a renamed field, a map key that stopped being a decimal string -- that is
 * exactly the failure this catches and a mocked service could not.
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
class DepthEndpointIT {

    private static final long ONE = 100_000_000L;

    /** Fractional prices on purpose: a ladder of round numbers would render identically
     *  whether or not the 1e8 scaling was undone correctly. */
    private static final long BID_BEST = 101_25000000L;
    private static final long BID_MID = 100_00000000L;
    private static final long BID_LOW = 99_50000000L;
    private static final long ASK_BEST = 103_00000000L;
    private static final long ASK_MID = 104_75000000L;
    private static final long ASK_HIGH = 105_00000000L;

    @BeforeAll
    static void requireInfrastructure() {
        requirePort("mysql", 3308);
        requirePort("redis", 6379);
        requirePort("mongodb", 27018);
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private MongoTemplate mongo;

    private final List<String> inserted = new ArrayList<>();
    private final List<String> tapesInserted = new ArrayList<>();

    @AfterEach
    void removeTestBooks() {
        for (String id : inserted) {
            mongo.remove(new Query(Criteria.where("_id").is(id)),
                    Market.SPOT.depthCollection());
        }
        for (String id : tapesInserted) {
            mongo.remove(new Query(Criteria.where("_id").is(id)),
                    Market.SPOT.tapeCollection());
        }
    }

    @Test
    void returnsTheTopOfBookAsDecimalStrings() {
        String symbol = insertBook("ITDEPTHREST", 42);

        JsonNode data = get(symbol, "?limit=20");

        assertThat(data.get("symbol").asText()).isEqualTo(symbol);
        assertThat(data.get("lastSequence").asLong()).isEqualTo(42L);

        // Strings, not JSON numbers -- a price handed to JavaScript as a double is the
        // whole reason money is a 1e8-scaled long everywhere behind this.
        assertThat(data.get("bids").get(0).get("price").isTextual()).isTrue();
        assertThat(data.get("bids").get(0).get("price").asText()).isEqualTo("101.25");
        assertThat(data.get("bids").get(0).get("quantity").asText()).isEqualTo("2");
        assertThat(data.get("asks").get(0).get("price").asText()).isEqualTo("103");
    }

    /** Best price first on both sides, which is the order a ladder is drawn in. */
    @Test
    void ordersBothSidesBestPriceFirst() {
        String symbol = insertBook("ITDEPTHORDER", 7);

        JsonNode data = get(symbol, "?limit=20");

        assertThat(prices(data.get("bids"))).containsExactly("101.25", "100", "99.5");
        assertThat(prices(data.get("asks"))).containsExactly("103", "104.75", "105");
    }

    /**
     * Past {@code TOP_LEVELS} the service reads the stored ladder instead of the
     * precomputed arrays. Both must describe the same book -- this is the one test that
     * exercises the two representations against each other through real Mongo.
     */
    @Test
    void reachingPastThePrecomputedTopReadsTheSameBook() {
        String symbol = insertBook("ITDEPTHDEEP", 7);

        JsonNode shallow = get(symbol, "?limit=20");
        JsonNode deep = get(symbol, "?limit=" + (DepthBook.TOP_LEVELS + 1));

        assertThat(prices(deep.get("bids"))).isEqualTo(prices(shallow.get("bids")));
        assertThat(prices(deep.get("asks"))).isEqualTo(prices(shallow.get("asks")));
    }

    /**
     * Several levels collapsing into one row, through the real endpoint. The grouped path
     * reads the stored ladder rather than the precomputed top, so this also proves the two
     * representations agree about what is in the book.
     */
    @Test
    void groupingCollapsesLevelsIntoBuckets() {
        String symbol = insertBook("ITDEPTHGROUP", 7);

        JsonNode data = get(symbol, "?grouping=1");

        // Bids 101.25, 100, 99.5 -> buckets 101, 100, 99 (rounded DOWN, away from spread).
        assertThat(prices(data.get("bids"))).containsExactly("101", "100", "99");
        // Asks 103, 104.75, 105 -> 103, 105, 105: the last two share a bucket once rounded
        // UP, so their sizes add rather than one replacing the other.
        assertThat(prices(data.get("asks"))).containsExactly("103", "105");
        assertThat(data.get("asks").get(1).get("quantity").asText()).isEqualTo("7"); // 3 + 4
    }

    /** Both sides round away from the spread, so a displayed price is the worst in its
     *  bucket -- never one nobody is actually offering. */
    @Test
    void groupingRoundsBidsDownAndAsksUp() {
        String symbol = insertBook("ITDEPTHROUND", 7);

        JsonNode data = get(symbol, "?grouping=10");

        assertThat(prices(data.get("bids"))).containsExactly("100", "90");
        assertThat(prices(data.get("asks"))).containsExactly("110");
    }

    @Test
    void anUnreadableGroupingIsRefused() {
        String symbol = insertBook("ITDEPTHBADGROUP", 7);

        JsonNode body = http.getForObject(
                "/bff/market/" + symbol + "/depth?grouping=abc", JsonNode.class);

        assertThat(body.get("code").asInt()).isEqualTo(6003);
    }

    /** A newly listed market exists and is valid, it simply has no orders yet. A 404 would
     *  send the user looking for a symbol that is right there in the list. */
    @Test
    void aSymbolWithNoBookReturnsAnEmptyLadderRatherThanNotFound() {
        JsonNode data = get("ITDEPTHNOBOOK", "");

        assertThat(data.get("bids")).isEmpty();
        assertThat(data.get("asks")).isEmpty();
        assertThat(data.get("lastSequence").asLong()).isZero();
    }

    /** The service throws on a non-positive limit, so without the controller's own clamp
     *  this is a 500 rather than a sensible default. */
    @Test
    void anAbsurdLimitFallsBackToTheDefaultInsteadOfFailing() {
        String symbol = insertBook("ITDEPTHLIMIT", 7);

        assertThat(get(symbol, "?limit=0").get("bids")).hasSize(3);
        assertThat(get(symbol, "?limit=-5").get("bids")).hasSize(3);
    }

    /**
     * The trades panel on page load. The socket carries everything after this and nothing
     * before it, so without this endpoint the panel is blank until someone trades.
     */
    @Test
    void returnsRecentTradesNewestFirst() {
        String symbol = insertTape("ITTRADES");

        JsonNode data = http.getForObject(
                "/bff/market/" + symbol + "/trades", JsonNode.class).get("data");

        assertThat(data).hasSize(3);
        assertThat(data.get(0).get("sequence").asLong()).isEqualTo(3L);
        assertThat(data.get(0).get("price").asText()).isEqualTo("102");
        assertThat(data.get(0).get("price").isTextual()).isTrue();
        assertThat(data.get(0).get("takerSide").asText()).isEqualTo("SELL");
    }

    /** The one assertion worth more than the rest: a public tape must never carry who
     *  traded or what they paid. Nothing fails if it does -- it just leaks. */
    @Test
    void tradesCarryNoCounterpartyOrFees() {
        String symbol = insertTape("ITTRADESPRIV");

        String body = http.getForObject(
                "/bff/market/" + symbol + "/trades", String.class);

        assertThat(body)
                .doesNotContain("userId").doesNotContain("UserId")
                .doesNotContain("feeBps").doesNotContain("FeeBps")
                .doesNotContain("maker").doesNotContain("taker" + "UserId");
    }

    @Test
    void aSymbolWithNoTradesReturnsAnEmptyList() {
        JsonNode data = http.getForObject(
                "/bff/market/ITNOTRADES/trades", JsonNode.class).get("data");

        assertThat(data).isEmpty();
    }

    // --- helpers ---

    /** The document shape {@code TradeTapeWriter} produces: oldest first. */
    private String insertTape(String prefix) {
        String symbol = prefix + (System.nanoTime() % 1_000_000);
        String id = TradeTape.idOf(Market.SPOT, symbol);
        tapesInserted.add(id);

        mongo.getCollection(Market.SPOT.tapeCollection()).insertOne(new Document("_id", id)
                .append("market", Market.SPOT.name())
                .append("symbol", symbol)
                .append("trades", List.of(
                        tapeTrade(1, 100 * ONE, "BUY"),
                        tapeTrade(2, 101 * ONE, "BUY"),
                        tapeTrade(3, 102 * ONE, "SELL")))
                .append("lastSequence", 3L)
                .append("updatedAt", java.util.Date.from(java.time.Instant.now())));
        return symbol;
    }

    private static Document tapeTrade(long sequence, long price, String takerSide) {
        return new Document("sequence", sequence)
                .append("price", price)
                .append("quantity", ONE)
                .append("quoteQuantity", price)
                .append("takerSide", takerSide)
                .append("executedAt", java.util.Date.from(
                        java.time.Instant.EPOCH.plusSeconds(sequence)));
    }

    private JsonNode get(String symbol, String queryString) {
        JsonNode body = http.getForObject(
                "/bff/market/" + symbol + "/depth" + queryString, JsonNode.class);
        return body.get("data");
    }

    private static List<String> prices(JsonNode levels) {
        List<String> prices = new ArrayList<>();
        levels.forEach(level -> prices.add(level.get("price").asText()));
        return prices;
    }

    /** Writes the same document shape {@code DepthWriter} produces: the full ladder as
     *  price-keyed maps, plus the precomputed best-first arrays. */
    private String insertBook(String prefix, long lastSequence) {
        String symbol = prefix + (System.nanoTime() % 1_000_000);
        String id = DepthBook.idOf(Market.SPOT, symbol);
        inserted.add(id);

        Document book = new Document("_id", id)
                .append("market", Market.SPOT.name())
                .append("symbol", symbol)
                .append("bids", new Document()
                        .append(String.valueOf(BID_LOW), 4 * ONE)
                        .append(String.valueOf(BID_BEST), 2 * ONE)
                        .append(String.valueOf(BID_MID), 3 * ONE))
                .append("asks", new Document()
                        .append(String.valueOf(ASK_HIGH), 4 * ONE)
                        .append(String.valueOf(ASK_BEST), 2 * ONE)
                        .append(String.valueOf(ASK_MID), 3 * ONE))
                .append("topBids", List.of(
                        level(BID_BEST, 2 * ONE), level(BID_MID, 3 * ONE),
                        level(BID_LOW, 4 * ONE)))
                .append("topAsks", List.of(
                        level(ASK_BEST, 2 * ONE), level(ASK_MID, 3 * ONE),
                        level(ASK_HIGH, 4 * ONE)))
                .append("lastSequence", lastSequence)
                .append("updatedAt", java.util.Date.from(java.time.Instant.now()));

        mongo.getCollection(Market.SPOT.depthCollection()).insertOne(book);
        return symbol;
    }

    private static Document level(long price, long quantity) {
        return new Document("price", price).append("quantity", quantity);
    }

    private static void requirePort(String service, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
        } catch (Exception e) {
            Assumptions.abort(service + " not reachable -- run `docker compose up -d`");
        }
    }
}
