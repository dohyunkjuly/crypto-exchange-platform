package com.exchange.spot.settlement.config;

import com.exchange.common.kafka.Replication;
import com.exchange.common.kafka.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares {@code exchange.spot.settlements.v1} instead of letting it be auto-created.
 *
 * <p>Auto-creation gives a topic whatever {@code num.partitions} the broker happens to
 * default to, which is not a number anyone chose. That has already caused a real problem on
 * this broker once -- see {@code EventTopicConfig}, where the snapshot-pointer topic came up
 * with 1 partition while its siblings got 8, none of it decided by anyone.
 *
 * <p><b>An identical copy of this class lives in web-bff</b>, because both apps publish here
 * and either may be the first to connect -- {@code BalanceChanged} comes from
 * {@code BalanceService} wherever it runs, so web-bff produces the moment someone places an
 * order. Whichever starts first creates the topic; the other's declaration is a no-op, since
 * Spring's {@code KafkaAdmin} treats an existing topic as already done. Duplicated rather
 * than shared for the reason {@code ConsumerLivenessHealthIndicator} is: a config class in a
 * library would mean every consumer of that library declaring a topic it may never touch.
 * Keep the two in step; they read the same property names.
 *
 * <p><b>Retention is short, and that is the point.</b> Nobody replays this topic: consumers
 * take a group per process and start at {@code latest}, because a reconnecting browser
 * refetches over REST. An hour is enough to inspect recent traffic while debugging and
 * stops the log growing for no reader. The command and event topics are the opposite --
 * {@code -1}, because losing one of those costs an order or a projection that can never be
 * rebuilt.
 */
@Configuration
public class SettlementTopicConfig {

    @Bean
    public NewTopic settlementTopic(@Value("${exchange.settlements.partition-count:8}") int partitionCount,
                                 @Value(Replication.FACTOR) int replicationFactor,
            @Value("${exchange.settlements.retention-ms:3600000}") long retentionMs) {
        return TopicBuilder.name(Topics.SPOT_SETTLEMENTS)
                .partitions(partitionCount)
                .replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(retentionMs))
                .build();
    }
}
