package com.exchange.spot.matching;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The matching engine.
 *
 * <p>It has no database and no HTTP endpoints for its actual job. It consumes commands
 * from Kafka, runs them through an order book, and publishes what happened. That is the
 * entire job, and keeping it that small is what makes it possible to reason about: the
 * component that decides who gets filled has no queries to slow it down and one
 * deterministic input — the log.
 *
 * <p>The one exception is {@code /actuator/health} (see application.yml's {@code
 * server.port} comment, and {@code health.ConsumerLivenessHealthIndicator}) — a side
 * door for a liveness probe to tell a wedged consumer thread apart from an idle one, not
 * a second public interface. It is the only thing registered on that port; nothing
 * about matching itself is reachable over the network.
 *
 * <p>Scheduling is for the heartbeat alone ({@code health.EngineHeartbeatPublisher}), on its
 * own thread; nothing scheduled may touch the book, which has no lock.
 */
@SpringBootApplication
@EnableScheduling
public class MatchingEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatchingEngineApplication.class, args);
    }
}
