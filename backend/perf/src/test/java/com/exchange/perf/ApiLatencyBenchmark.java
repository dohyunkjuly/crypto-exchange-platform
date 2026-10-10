package com.exchange.perf;

import static com.exchange.perf.Stack.*;

import com.exchange.perf.report.Report;
import com.exchange.perf.report.Sections;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What a bot author feels: a signed order accepted, a book read, and the weight limit tripping
 * where the documentation says. Ten keys at once, each placing resting bids far below the
 * market at the pace its own order limit allows, then cancelling them all.
 */
@Tag("performance")
class ApiLatencyBenchmark {

    private static final int KEYS = 10;
    private static final int ORDERS_PER_KEY = 200;

    @BeforeAll
    static void requireInfrastructure() throws Exception {
        requireStack();
    }

    @Test
    void place_read_and_the_limit() throws Exception {
        List<Account> keys = accounts(KEYS, "perf-api", "10000000");
        String price = price(lastPrice() * 0.5);

        List<Long> place = placeMany(keys, "BUY", price, "0.001", KEYS * ORDERS_PER_KEY);
        Report.latency(Sections.API, Sections.POST_ORDER, place,
                "2,000 signed limit orders from 10 keys at once, each key under the advertised order limit, each answered 202", null);
        Report.latency(Sections.HEADLINE, Sections.API_ROUND_TRIP, place,
                "2,000 signed POST /v1/orders from 10 keys at once",
                "Signature check, eligibility, funds lock, outbox write and commit, in one round trip.");

        List<Long> depth = new ArrayList<>();
        HttpRequest read = HttpRequest.newBuilder(URI.create(API + "/v1/markets/" + SYMBOL + "/depth?limit=50")).GET().build();
        for (int i = 0; i < 2_000; i++) {
            long t0 = System.nanoTime();
            send(read);
            depth.add(System.nanoTime() - t0);
        }
        Report.latency(Sections.API, Sections.GET_DEPTH, depth, "2,000 reads of the top 50 levels", null);

        // The weight limit trips where the API says it does. The local stack advertises a
        // limit lifted for the bots, far past what one run should send; then this is skipped
        // and the row stays empty rather than claiming a number nobody measured.
        int advertised = advertisedLimit("REQUEST_WEIGHT", "1m");
        if (advertised <= 5_000) {
            Thread.sleep(60_000);
            HttpRequest time = HttpRequest.newBuilder(URI.create(API + "/v1/time")).GET().build();
            int n = 0;
            while (n < advertised + 100 && send(time).statusCode() != 429) n++;
            Report.value(Sections.API, Sections.RATE_LIMIT_TRIPS, n, "requests",
                    "weight-1 requests from one address until the first 429; the API advertises " + advertised + " a minute", null);
        } else {
            System.out.println("PERF skipped the weight-limit trip: the stack advertises " + advertised + " a minute (lifted for the bots)");
        }

        for (Account key : keys) send(signed(key, "DELETE", "/v1/orders/open", null));
    }
}
