package com.exchange.perf;

import static com.exchange.perf.Stack.*;

import com.exchange.perf.report.Report;
import com.exchange.perf.report.Sections;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What a trader feels: the time from clicking Buy to seeing the fill. Ten accounts rest sells
 * at the last price, so the market does not move; another, logged in like a browser with its
 * private socket open, buys at that price and the clock stops when the socket says FILLED.
 * The path is the real one: web-bff, outbox, Kafka, engine, Kafka, web-bff, socket. web-bff's
 * per-address request guard is lifted for the run (PERF_BFF_RATE_LIMIT), or one address could
 * not send a thousand orders in a minute.
 */
@Tag("performance")
class OrderToFillBenchmark {

    private static final int FILLS = 1_000;

    @BeforeAll
    static void requireInfrastructure() throws Exception {
        requireStack();
    }

    @Test
    void click_to_fill() throws Exception {
        List<Account> all = accounts(11, "perf-fill", "10000000");
        List<Account> makers = all.subList(0, 10);
        Account taker = all.get(10);
        String takerCookie = login(taker);
        String price = price(lastPrice());

        // The resting side, placed ahead of time over the API so the taker's clock measures only
        // the taker. A tenth more than needed: a sell at the last price can meet a bid already
        // there and fill at once, and every taker order must still find one.
        placeMany(makers, "SELL", price, "0.001", FILLS + FILLS / 10);
        Thread.sleep(2_000);

        Map<String, Long> sentAt = new ConcurrentHashMap<>();
        List<Long> latencies = new ArrayList<>();
        CountDownLatch filled = new CountDownLatch(FILLS);
        WebSocket ws = socket(wsUrl(BFF) + "/bff/ws/user", takerCookie, frame -> {
            try {
                JsonNode f = JSON.readTree(frame);
                if (!"orderUpdate".equals(f.path("type").asText())) return;
                JsonNode d = f.get("data");
                Long t0 = sentAt.get(d.path("orderId").asText());
                if (t0 != null && "FILLED".equals(d.path("status").asText())) {
                    synchronized (latencies) { latencies.add(System.nanoTime() - t0); }
                    filled.countDown();
                }
            } catch (Exception ignored) {
                // Not a frame this benchmark reads.
            }
        });
        ws.request(1);

        for (int i = 0; i < FILLS; i++) {
            long t0 = System.nanoTime();
            HttpResponse<String> r = send(json("POST", BFF + "/bff/orders", limit("BUY", price, "0.001"), takerCookie));
            String orderId = JSON.readTree(r.body()).get("data").get("orderId").asText();
            sentAt.put(orderId, t0);
            Thread.sleep(5); // a human-paced stream, not a flood: this measures the path, not the queue
        }
        // Nearly all is enough: a buy that lands between two makers' sells can rest, and one
        // missing sample does not move a percentile. Under 95% is a broken run.
        filled.await(120, TimeUnit.SECONDS);
        if (latencies.size() < FILLS * 95 / 100) {
            throw new IllegalStateException("only " + latencies.size() + " of " + FILLS + " fills arrived");
        }
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        for (Account m : makers) send(signed(m, "DELETE", "/v1/orders/open", null));
        send(json("DELETE", BFF + "/bff/orders?symbol=" + SYMBOL, null, takerCookie));
        Report.latency(Sections.HEADLINE, Sections.ORDER_TO_FILL, latencies,
                "1,000 limit buys through web-bff, each filling a resting sell; time until the private socket says FILLED",
                "The whole path a click takes: HTTP, outbox, Kafka, the engine, Kafka, the socket.");
    }
}
