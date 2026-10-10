package com.exchange.spot.matching.config;

import com.exchange.common.kafka.Replication;
import com.exchange.common.kafka.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The heartbeat topic ({@link Topics#SPOT_ENGINE_STATUS}), one partition per command
 * partition, so a reader can tell which partitions it hears from by where it hears them.
 *
 * <p>A minute of retention, the one topic here allowed to lose everything: a heartbeat is
 * worth nothing a few seconds after it was sent, and the API judges a partition by whether it
 * heard one recently, never by what the topic holds.
 */
@Configuration
public class EngineStatusTopicConfig {

    @Bean
    public NewTopic engineStatusTopic(@Value("${engine.partition-count}") int partitionCount,
                                      @Value(Replication.FACTOR) int replicationFactor) {
        return TopicBuilder.name(Topics.SPOT_ENGINE_STATUS)
                .partitions(partitionCount)
                .replicas(replicationFactor)
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(Replication.minInSync(replicationFactor)))
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(60_000))
                .build();
    }
}
