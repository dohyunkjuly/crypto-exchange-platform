package com.exchange.perf.report;

/**
 * The report's sections and the metrics each shows, in order. Named here once so a benchmark
 * in the engine module and one in this module put the same thing under the same heading, and
 * the rendered page keeps its shape from run to run.
 */
public final class Sections {

    public static final String HEADLINE = "Headline";
    public static final String ENGINE = "Matching engine";
    public static final String SETTLEMENT = "Settlement";
    public static final String API = "Public API";
    public static final String CLUSTER = "Three brokers";

    static final String[] ALL = {HEADLINE, ENGINE, SETTLEMENT, API, CLUSTER};

    // Headline
    public static final String ORDERS_PER_SECOND = "Orders per second, one partition";
    public static final String ORDER_TO_FILL = "Order to fill, through the website";
    public static final String API_ROUND_TRIP = "Place an order over the API";
    public static final String CONCURRENT_SOCKETS = "Market sockets served at once";
    public static final String MEMORY_PER_ORDER = "Memory per resting order";
    public static final String RECOVERY_TIME = "Back after a crash";

    // Engine
    public static final String CEILING = "No-fill ceiling";
    public static final String CROSSING = "Realistic crossing";
    public static final String CASCADE_WINDOWS = "Slowest window while stops fire";
    public static final String CASCADE_COST = "Pause while 5,000 stops fire";
    public static final String SNAPSHOT_OVERHEAD = "Snapshot overhead at snapshot-every=";
    public static final String SNAPSHOT_BUILD = "Snapshot build time at ";
    public static final String MEMORY_AT = "Heap per order at ";
    public static final String VARIANCE = "Run-to-run spread of the ceiling";

    // Settlement
    public static final String FILLS_PER_SECOND = "Fills settled per second";
    public static final String SETTLEMENT_LAG = "Event to balance committed";

    // API
    public static final String POST_ORDER = "POST /v1/orders";
    public static final String GET_DEPTH = "GET /v1/markets/{symbol}/depth?limit=50";
    public static final String RATE_LIMIT_TRIPS = "Weight limit trips at the documented request";

    // Cluster
    public static final String BROKER_LOSS_STALL = "Stall when one broker dies";
    public static final String BROKER_LOSS_LOST = "Commands lost when one broker dies";

    /** The order rows appear in within a section; a metric whose name starts with one of these sorts there. */
    static final String[] ORDER = {
        ORDERS_PER_SECOND, ORDER_TO_FILL, API_ROUND_TRIP, CONCURRENT_SOCKETS, MEMORY_PER_ORDER, RECOVERY_TIME,
        CEILING, CROSSING, VARIANCE, CASCADE_WINDOWS, CASCADE_COST, SNAPSHOT_OVERHEAD, MEMORY_AT, SNAPSHOT_BUILD,
        FILLS_PER_SECOND, SETTLEMENT_LAG, POST_ORDER, GET_DEPTH, RATE_LIMIT_TRIPS, BROKER_LOSS_STALL, BROKER_LOSS_LOST,
    };

    static int rank(String metric) {
        for (int i = 0; i < ORDER.length; i++) {
            if (metric.startsWith(ORDER[i])) return i;
        }
        return ORDER.length;
    }

    private Sections() {
    }
}
