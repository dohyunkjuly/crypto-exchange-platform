package com.exchange.perf;

import static com.exchange.perf.Stack.*;

import com.exchange.perf.report.Report;
import com.exchange.perf.report.Sections;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * How many people can watch at once. Market sockets are opened in steps, each subscribed to
 * the trade tape; at every step the benchmark makes one trade itself and the step passes when
 * every socket opened so far reports it within ten seconds. The report carries the largest
 * step that passed, up to the budget, which this machine's open-file limit bounds as much as
 * web-bff does.
 */
@Tag("performance")
class SocketsBenchmark {

    private static final int[] STEPS = {100, 250, 500, 1_000, 2_000};

    @BeforeAll
    static void requireInfrastructure() throws Exception {
        requireStack();
    }

    @Test
    void market_sockets_all_receiving() throws Exception {
        List<Account> pair = accounts(2, "perf-sockets", "10000000");
        String price = price(lastPrice());
        List<WebSocket> open = new ArrayList<>();
        AtomicInteger frames = new AtomicInteger();
        int reached = 0;
        try {
            for (int target : STEPS) {
                try {
                    while (open.size() < target) {
                        WebSocket ws = socket(wsUrl(BFF) + "/bff/ws/market", null, f -> { if (f.contains("\"trade\"") && f.contains(price)) frames.incrementAndGet(); });
                        ws.sendText("{\"action\":\"subscribe\",\"channel\":\"trade\",\"symbol\":\"" + SYMBOL + "\"}", true);
                        ws.request(1);
                        open.add(ws);
                    }
                } catch (Exception e) {
                    System.out.println("PERF sockets: could not open more than " + open.size() + " (" + e.getMessage() + ")");
                    break;
                }
                Thread.sleep(1_000);
                frames.set(0);
                send(signed(pair.get(0), "POST", "/v1/orders", limit("SELL", price, "0.001")));
                Thread.sleep(300);
                send(signed(pair.get(1), "POST", "/v1/orders", limit("BUY", price, "0.001")));
                long deadline = System.currentTimeMillis() + 10_000;
                while (frames.get() < open.size() && System.currentTimeMillis() < deadline) Thread.sleep(100);
                if (frames.get() < open.size()) {
                    System.out.println("PERF sockets: " + frames.get() + " of " + open.size() + " saw the trade in 10 s");
                    break;
                }
                reached = open.size();
            }
        } finally {
            for (WebSocket ws : open) ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
        Report.value(Sections.HEADLINE, Sections.CONCURRENT_SOCKETS, reached, "sockets",
                "market sockets from one machine on the trade tape, all reporting a fresh trade within 10 s; stepped up to 2,000",
                "One web-bff, one laptop as the client. Horizontal: more web-bff instances behind nginx, each a full fan-out.");
    }
}
