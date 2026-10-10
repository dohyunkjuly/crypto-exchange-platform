package com.exchange.admin.bff.testing;

import java.util.concurrent.ThreadLocalRandom;

/**
 * An id for a customer row a test inserts by SQL: the column no longer counts
 * (V14__user_id_not_counted.sql), and the app draws ten digits at random, so a fixture does too.
 */
public final class Uid {

    private Uid() {
    }

    public static long next() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L);
    }
}
