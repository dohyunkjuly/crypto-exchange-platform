package com.exchange.marketdata.ingest;

import com.exchange.common.kafka.Replication;
import com.exchange.common.kafka.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares {@code exchange.spot.depth.v1} rather than letting the broker auto-create it with
 * whatever partition count it defaults to -- the reasoning in web-bff's
 * {@code SettlementTopicConfig}. This app is the only publisher, so the one declaration lives
 * here. Retention is short for the reason settlements' is: nobody replays it, a consumer starts
 * at {@code latest} and resyncs from the stored book.
 */
@Configuration
public class DepthTopicConfig {

    @Bean
    public NewTopic depthTopic(@Value("${exchange.depth.partition-count:8}") int partitionCount,
                                 @Value(Replication.FACTOR) int replicationFactor,
                               @Value("${exchange.depth.retention-ms:3600000}") long retentionMs) {
        return TopicBuilder.name(Topics.SPOT_DEPTH)
                .partitions(partitionCount)
                .replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(retentionMs))
                .build();
    }
}
