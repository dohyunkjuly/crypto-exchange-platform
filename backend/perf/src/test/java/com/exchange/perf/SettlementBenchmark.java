package com.exchange.perf;

import static com.exchange.perf.Stack.*;

import com.exchange.perf.report.Report;
import com.exchange.perf.report.Sections;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * How fast fills become rows. Twenty accounts rest 2,200 small sells at the last price; another
 * takes them all with one order. Settlement then writes 2,000 trades and their balance moves,
 * and this watches MySQL: how long until every trade is there, and per trade, how long after
 * the engine executed it the row was committed ({@code created_at - executed_at}).
 */
@Tag("performance")
class SettlementBenchmark {

    private static final int FILLS = 2_000;

    @BeforeAll
    static void requireInfrastructure() throws Exception {
        requireStack();
    }

    @Test
    void two_thousand_fills_in_one_order() throws Exception {
        // Twenty makers: an account may hold 200 open orders a market, and 2,200 sells rest here.
        List<Account> all = accounts(21, "perf-settle", "10000000");
        List<Account> makers = all.subList(0, 20);
        Account taker = all.get(20);
        String price = price(lastPrice());
        placeMany(makers, "SELL", price, "0.001", FILLS + FILLS / 10);
        Thread.sleep(3_000);

        long t0 = System.currentTimeMillis();
        send(signed(taker, "POST", "/v1/orders", limit("BUY", price, String.format("%.3f", FILLS * 0.001))));
        try (Connection db = DriverManager.getConnection(MYSQL, "exchange", "exchange")) {
            int rows = 0;
            long settledAt = 0;
            while (rows < FILLS && System.currentTimeMillis() - t0 < 120_000) {
                Thread.sleep(100);
                rows = count(db, taker.userId());
                settledAt = System.currentTimeMillis();
            }
            if (rows < FILLS) throw new IllegalStateException("only " + rows + " of " + FILLS + " trades settled");
            Report.value(Sections.SETTLEMENT, Sections.FILLS_PER_SECOND, FILLS / ((settledAt - t0) / 1000.0), "fills/s",
                    "one order filling 2,000 resting orders; from the 202 until every trade row exists", null);
            for (Account m : makers) send(signed(m, "DELETE", "/v1/orders/open", null));
            Report.latency(Sections.SETTLEMENT, Sections.SETTLEMENT_LAG, lags(db, taker.userId()),
                    "per trade, the row's created_at minus the engine's executed_at",
                    "How far the database trails the book: balances are final this long after a fill.");
        }
    }

    private static int count(Connection db, long userId) throws Exception {
        try (PreparedStatement ps = db.prepareStatement("SELECT COUNT(*) FROM spot_trades WHERE buyer_user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static List<Long> lags(Connection db, long userId) throws Exception {
        List<Long> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement("SELECT executed_at, created_at FROM spot_trades WHERE buyer_user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp executed = rs.getTimestamp(1), created = rs.getTimestamp(2);
                    long nanos = (created.getTime() - executed.getTime()) * 1_000_000L + (created.getNanos() - executed.getNanos()) % 1_000_000;
                    out.add(Math.max(0, nanos));
                }
            }
        }
        return out;
    }
}
