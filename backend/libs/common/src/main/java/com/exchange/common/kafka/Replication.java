package com.exchange.common.kafka;

/**
 * How many brokers hold every record of every topic this exchange creates.
 *
 * <p>One value for all of them, read from {@code KAFKA_REPLICATION_FACTOR}: a laptop runs one
 * broker, a deployment runs three, and a topic that outlives a broker needs a copy on another.
 * It applies when a topic is created. Raising it on a cluster whose topics already exist is a
 * partition reassignment, an operator's action, not a restart.
 *
 * <p>{@link #minInSync} is what makes {@code acks=all} mean something: with three copies and two
 * required in sync, one broker can be lost without losing a write or stopping the producers.
 * With one copy it is one, which is the only value a single broker can satisfy.
 */
public final class Replication {

    /** The Spring placeholder every topic bean reads; one default, one place. */
    public static final String FACTOR = "${KAFKA_REPLICATION_FACTOR:1}";

    private Replication() {
    }

    public static int minInSync(int replicationFactor) {
        return Math.max(1, replicationFactor - 1);
    }
}
