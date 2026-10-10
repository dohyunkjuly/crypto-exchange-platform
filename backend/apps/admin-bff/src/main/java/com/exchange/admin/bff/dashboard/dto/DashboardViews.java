package com.exchange.admin.bff.dashboard.dto;

import com.exchange.common.money.Money;
import java.time.Instant;
import java.util.List;

/** The dashboard screen's shapes. */
public final class DashboardViews {

    private DashboardViews() {
    }

    /** A figure today so far, and the same span of yesterday. Money as decimal strings. */
    public record Compare(String today, String yesterday) {
    }

    /** One market's day. {@code quote} is what its volume is counted in. */
    public record Pair(String symbol, String quote, String volumeToday, String volumeYesterday,
                       long tradesToday) {
    }

    public record Dashboard(Instant asOf, Compare signups, Compare trades, Compare volume, Compare fees,
                            List<Pair> pairs) {
    }
}
