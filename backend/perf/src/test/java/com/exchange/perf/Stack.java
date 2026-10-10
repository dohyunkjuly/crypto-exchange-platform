package com.exchange.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Assumptions;

/**
 * The running compose stack as a customer, a bot and a reader see it: web-bff on 8081 with a
 * cookie session, spot-open-api on 8080 with a signed key, their sockets, and MySQL on 3308.
 * Accounts come from web-bff's dev endpoint (the {@code dev} profile the local stack runs),
 * each verified, keyed and funded; nothing here needs a browser.
 */
final class Stack {

    static final String BFF = System.getenv().getOrDefault("PERF_BFF", "http://127.0.0.1:8081");
    static final String API = System.getenv().getOrDefault("PERF_API", "http://127.0.0.1:8080");
    static final String MYSQL = System.getenv().getOrDefault("PERF_MYSQL", "jdbc:mysql://127.0.0.1:3308/exchange");
    static final String SYMBOL = System.getenv().getOrDefault("PERF_SYMBOL", "BTCUSDT");

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** One dev account: the only copy of its API secret and TOTP seed. */
    record Account(String email, String password, long userId, String apiKey, String apiSecret, String totpSecret) {
    }

    private Stack() {
    }

    static void requireStack() throws Exception {
        requirePort("web-bff", 8081);
        requirePort("spot-open-api", 8080);
        requirePort("mysql", 3308);
        awaitDrained();
    }

    static final String KAFKA = System.getenv().getOrDefault("PERF_KAFKA_BOOTSTRAP", "localhost:9092");

    /**
     * Waits until settlement and market-data have read the event log to its end. An engine
     * benchmark leaves a million events behind it, and a fill timed while they are still being
     * chewed through measures the backlog, not the system. Up to ten minutes, then it gives up
     * and says so.
     */
    static void awaitDrained() throws Exception {
        try (org.apache.kafka.clients.admin.AdminClient admin = org.apache.kafka.clients.admin.AdminClient.create(
                Map.of(org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA))) {
            long deadline = System.currentTimeMillis() + 600_000;
            while (true) {
                long lag = 0;
                for (String group : List.of("spot-settlement", "market-data")) {
                    Map<org.apache.kafka.common.TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committed =
                            admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
                    if (committed.isEmpty()) continue;
                    Map<org.apache.kafka.common.TopicPartition, org.apache.kafka.clients.admin.OffsetSpec> latest = new java.util.HashMap<>();
                    committed.keySet().forEach(tp -> latest.put(tp, org.apache.kafka.clients.admin.OffsetSpec.latest()));
                    var ends = admin.listOffsets(latest).all().get();
                    for (var e : committed.entrySet()) {
                        lag += Math.max(0, ends.get(e.getKey()).offset() - e.getValue().offset());
                    }
                }
                if (lag < 2_000) return;
                if (System.currentTimeMillis() > deadline) {
                    System.out.println("PERF settlement/market-data still " + lag + " events behind after ten minutes; measuring anyway");
                    return;
                }
                System.out.println("PERF waiting for settlement and market-data to catch up: " + lag + " events behind");
                Thread.sleep(5_000);
            }
        }
    }

    static void requirePort(String service, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
        } catch (IOException e) {
            Assumptions.abort(service + " not reachable on " + port + " -- docker compose --profile full up -d first");
        }
    }

    // ------------------------------------------------------------------ accounts

    /** Funded with {@code fund} of every asset; the dev endpoint is the faucet. */
    static List<Account> accounts(int count, String label, String fund) throws Exception {
        JsonNode body = post(BFF + "/dev/accounts", Map.of("count", count, "label", label, "fund", fund), null).get("data");
        if (body == null) throw new IllegalStateException("dev endpoint absent: the stack is not running the dev profile");
        String password = body.get("password").asText();
        List<Account> out = new ArrayList<>();
        for (JsonNode a : body.get("accounts")) {
            out.add(new Account(a.get("email").asText(), password, a.get("userId").asLong(),
                    a.get("apiKey").asText(), a.get("apiSecret").asText(), a.get("totpSecret").asText()));
        }
        // The dev endpoint spends two codes making an account -- this step's to confirm the
        // authenticator, the next step's to mint the key -- and a code is single-use, so the
        // first login that is not a replay is two steps away.
        long now = System.currentTimeMillis();
        Thread.sleep((now / 30_000 + 2) * 30_000 - now + 500);
        return out;
    }

    /** The session cookie after password and TOTP, as the browser would hold it. */
    static String login(Account a) throws Exception {
        HttpResponse<String> first = send(json("POST", BFF + "/bff/auth/login", Map.of("email", a.email(), "password", a.password()), null));
        JsonNode data = JSON.readTree(first.body()).get("data");
        String token = data.get("challengeToken").asText();
        HttpResponse<String> second = send(json("POST", BFF + "/bff/auth/login/mfa",
                Map.of("challengeToken", token, "code", Totp.now(a.totpSecret())), null));
        return second.headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith("session="))
                .map(c -> c.substring(0, c.indexOf(';')))
                .findFirst().orElseThrow(() -> new IllegalStateException("no session cookie: " + second.body()));
    }

    // ------------------------------------------------------------------ web-bff

    static JsonNode post(String url, Object body, String cookie) throws Exception {
        return JSON.readTree(send(json("POST", url, body, cookie)).body());
    }

    static HttpRequest json(String method, String url, Object body, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (cookie != null) b.header("cookie", cookie);
        return b.build();
    }

    static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------ open API

    /** A signed request: {@code HMAC-SHA256(secret, timestamp + method + path + body)}. */
    static HttpRequest signed(Account a, String method, String pathWithQuery, Object body) throws Exception {
        String raw = body == null ? "" : JSON.writeValueAsString(body);
        String ts = String.valueOf(System.currentTimeMillis());
        String sign = hmac(a.apiSecret(), ts + method + pathWithQuery + raw);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + pathWithQuery))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .header("X-ACCESS-KEY", a.apiKey())
                .header("X-ACCESS-TIMESTAMP", ts)
                .header("X-ACCESS-SIGN", sign)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(raw));
        return b.build();
    }

    static String hmac(String secret, String canonical) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    /** The limits this API advertises on {@code GET /v1/markets/info}, by type and window. */
    static int advertisedLimit(String type, String window) throws Exception {
        JsonNode info = JSON.readTree(send(HttpRequest.newBuilder(URI.create(API + "/v1/markets/info")).GET().build()).body());
        for (JsonNode l : info.get("data").get("rateLimits")) {
            if (type.equals(l.get("type").asText()) && window.equals(l.get("window").asText())) return l.get("limit").asInt();
        }
        throw new IllegalStateException("no advertised limit " + type + "/" + window);
    }

    /**
     * {@code total} resting orders spread over the keys, each key paced under the order limit
     * the API advertises, so the book is built without a single 429. Returns the round-trip
     * time of every placement, which is what the API benchmark reports.
     */
    static List<Long> placeMany(List<Account> keys, String side, String price, String quantity, int total) throws Exception {
        long pauseMs = Math.max(0, 10_000 / Math.max(1, advertisedLimit("ORDERS", "10s")) + 1);
        List<Long> times = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(keys.size());
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        int perKey = (int) Math.ceil(total / (double) keys.size());
        for (Account key : keys) {
            futures.add(pool.submit(() -> {
                for (int i = 0; i < perKey; i++) {
                    long t0 = System.nanoTime();
                    HttpResponse<String> r = send(signed(key, "POST", "/v1/orders", limit(side, price, quantity)));
                    if (r.statusCode() != 202) throw new IllegalStateException(r.statusCode() + " " + r.body());
                    times.add(System.nanoTime() - t0);
                    if (pauseMs > 1) Thread.sleep(pauseMs);
                }
                return null;
            }));
        }
        for (java.util.concurrent.Future<?> f : futures) f.get();
        pool.shutdown();
        return times;
    }

    static Map<String, Object> limit(String side, String price, String quantity) {
        return Map.of("symbol", SYMBOL, "side", side, "orderType", "LIMIT", "timeInForce", "GTC", "price", price, "quantity", quantity);
    }

    // ------------------------------------------------------------------ sockets

    /** A socket that hands every text frame to {@code onFrame}; {@code cookie} may be null. */
    static WebSocket socket(String url, String cookie, Consumer<String> onFrame) throws Exception {
        WebSocket.Builder b = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        if (cookie != null) b.header("cookie", cookie);
        return b.buildAsync(URI.create(url), new WebSocket.Listener() {
            private final StringBuilder partial = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                partial.append(data);
                if (last) {
                    onFrame.accept(partial.toString());
                    partial.setLength(0);
                }
                ws.request(1);
                return null;
            }
        }).get(10, TimeUnit.SECONDS);
    }

    static String wsUrl(String http) {
        return http.replaceFirst("^http", "ws");
    }

    /** The last trade price. Benchmarks trade at it, never away from it: a trade away from the
     *  market becomes the last price, and the next run would compound that. */
    static double lastPrice() throws Exception {
        JsonNode t = JSON.readTree(send(HttpRequest.newBuilder(URI.create(API + "/v1/markets/" + SYMBOL + "/ticker")).GET().build()).body());
        return t.get("data").get("lastPrice").asDouble();
    }

    static String price(double p) {
        return String.format(java.util.Locale.ROOT, "%.2f", p);
    }
}
