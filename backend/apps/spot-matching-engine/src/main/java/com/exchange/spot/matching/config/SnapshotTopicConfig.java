package com.exchange.spot.matching.config;

import com.exchange.common.kafka.Replication;
import com.exchange.spot.matching.snapshot.SnapshotStore;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.apache.kafka.clients.admin.NewTopic;

/**
 * Declares the snapshot-pointer topic instead of letting it be auto-created.
 *
 * <p>Auto-creation isn't just untidy here, it's wrong. A broker creates a topic with its
 * own {@code num.partitions} default -- 1, unless someone changed it -- but this topic's
 * addressing scheme needs exactly as many partitions as the command topic: recovery for
 * command partition 3 reads pointer partition 3. Get 1 partition instead of 8, and the
 * engine asks for offsets on partitions that don't exist -- which doesn't fail, it HANGS,
 * once per missing partition, at startup.
 *
 * <p>Compaction is declared here for the same reason: it's what keeps this topic from
 * growing forever, and {@link SnapshotStore} assumes it's on. Set by hand on one broker,
 * it's a fact about that one machine. Set here, it's a fact about the system.
 */
@Configuration
public class SnapshotTopicConfig {

    /**
     * Must match the command topic's partition count -- not the partitions THIS instance
     * owns, which is a deployment decision, but the total, which is a capacity decision
     * fixed when the command topic was created and not reducible afterwards.
     */
    @Bean
    public NewTopic snapshotPointerTopic(@Value("${engine.partition-count}") int partitionCount,
                                 @Value(Replication.FACTOR) int replicationFactor) {
        return TopicBuilder.name(SnapshotStore.TOPIC)
                .partitions(partitionCount)
                .replicas(replicationFactor)
                // acks=all waits for this many copies; one broker may die without a lost write.
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(Replication.minInSync(replicationFactor)))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT)
                // A compacted topic is only compacted once a segment closes. Left at the
                // 1 GB default the active segment never rolls in practice and the topic
                // grows for ever while claiming to be compacted.
                .config(TopicConfig.SEGMENT_BYTES_CONFIG, String.valueOf(32 * 1024 * 1024))
                .build();
    }
}
