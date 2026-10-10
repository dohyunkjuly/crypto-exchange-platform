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
 * Declares the command topic instead of letting it be auto-created.
 *
 * <p>Auto-creation would give it whatever {@code retention.ms} the broker defaults to --
 * seven days on a stock image. That's a problem, because this log is the engine's only
 * source of truth: when there's no usable snapshot, recovery falls back to
 * {@code seekToBeginning}, which seeks to the earliest RETAINED offset -- not offset 0.
 * Past the retention window, those stop being the same thing, and a rebuild would silently
 * drop every order older than the window while still logging "rebuilding from offset 0" as
 * if nothing was lost.
 *
 * <p>Same class of bug as the pointer topic: a broker default quietly standing in for a
 * decision nobody actually made. Same fix: put the decision in code, so it survives a
 * move to a new cluster instead of depending on whichever broker happened to be running
 * when the topic was first touched.
 */
@Configuration
public class CommandTopicConfig {

    @Bean
    public NewTopic commandTopic(@Value("${engine.partition-count}") int partitionCount,
                                 @Value(Replication.FACTOR) int replicationFactor) {
        return TopicBuilder.name(Topics.SPOT_COMMANDS)
                .partitions(partitionCount)
                .replicas(replicationFactor)
                // acks=all waits for this many copies; one broker may die without a lost write.
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(Replication.minInSync(replicationFactor)))
                // Never expire. A snapshot is an optimisation on top of this log, not a
                // replacement for any part of it expiring.
                //
                // On a laptop this keeps every command on the broker's own disk, growing
                // without bound. A deployment enables Kafka's tiered storage instead:
                // local.retention.ms keeps a week on broker disk while retention.ms stays -1
                // and the rest ages out to object storage, still readable through the same
                // seekToBeginning path. That needs a broker-side remote-storage-manager
                // plugin, which is the operator's broker setup, not this code
                // (docs/reference/white-label.md "Going live").
                .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
                .build();
    }
}
