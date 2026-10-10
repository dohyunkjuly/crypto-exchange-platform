package com.exchange.spot.copy;

import com.exchange.copy.follow.CopyFollowService;
import com.exchange.fee.FeeQueryService;
import com.exchange.spot.order.OrderService;
import com.exchange.spot.outbox.CommandOutboxPublisher;
import com.exchange.spot.outbox.CommandOutboxService;
import com.exchange.spot.outbox.CommandOutboxSweeper;
import com.exchange.spot.symbol.CommandPartitions;
import com.exchange.spot.symbol.EnginePresence;
import com.exchange.spot.symbol.EnginePresenceConsumerConfig;
import com.exchange.spot.symbol.EnginePresenceConsumerGroup;
import com.exchange.user.eligibility.EligibilityService;
import com.exchange.user.subaccount.SubAccountService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The copier (docs/reference/copy-trading.md). Reads {@code exchange.spot.events.v1}, and when a
 * lead account's order closes with something filled, places the same trade, scaled, in each
 * follower's copy account.
 *
 * <p>It is a client of the order path and never writes an order or a balance itself:
 * settlement stays the only writer of both. Its orders go through {@code OrderService} as
 * web-bff's do, so fees, limits and locked funds apply as to any order.
 *
 * <p>No HTTP of its own; the port is the health probe's, as in spot-settlement.
 */
// The order path by class, not by scanning com.exchange.spot: that package also holds the
// sockets' Kafka consumers, which this app has no browser to serve. The same for the gate:
// com.exchange.user holds ApiKeyService, which needs secrets this app has no business with.
// Mongo is excluded for settlement's reason: spot brings its starter in, nothing here reads it.
@SpringBootApplication(
        scanBasePackages = {"com.exchange.spot.copy", "com.exchange.copy", "com.exchange.core",
                "com.exchange.wallet"},
        exclude = {MongoAutoConfiguration.class, MongoDataAutoConfiguration.class,
                MongoRepositoriesAutoConfiguration.class})
@EntityScan("com.exchange")
@EnableJpaRepositories("com.exchange")
@Import({OrderService.class, CommandOutboxService.class, CommandOutboxPublisher.class, CommandOutboxSweeper.class,
        CommandPartitions.class, FeeQueryService.class, EligibilityService.class,
        // The engine's heartbeat, which OrderService refuses orders without.
        EnginePresence.class, EnginePresenceConsumerGroup.class, EnginePresenceConsumerConfig.class,
        // CopyFollowService is scanned with the rest of com.exchange.copy and makes copy accounts.
        SubAccountService.class})
// The outbox's sweeper, and the sweep that finishes closing a copy account.
@EnableScheduling
public class SpotCopyApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpotCopyApplication.class, args);
    }
}
