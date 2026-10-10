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
 * Declares the event topic instead of letting it be auto-created.
 *
 * <p>Auto-creation gives it whatever {@code num.partitions} the broker defaults to, which
 * has nothing to do with how many partitions this topic actually needs: exactly as many
 * as the command topic. Each event is keyed by symbol, so a symbol's trades stay in order
 * only if that partition count never quietly drifts.
 *
 * <p>Not a hypothetical risk -- it already happened once. The snapshot-pointer topic
 * auto-created with 1 partition on this same broker, while this topic and the command
 * topic happened to get 8. Same mechanism, three different outcomes, none of them chosen
 * by anyone. Recreate this topic fresh -- a wiped volume, a new environment -- and it gets
 * whatever the broker invents that day, not the 8 it happens to have now.
 *
 * <p><b>Never expires</b>, for the same reason the command topic does not. This is the only
 * output the engine has, and every downstream read model -- candles, L2 depth, the ticker --
 * is a projection of it. Rebuilding any of them from scratch means replaying this topic
 * from the beginning, so its retention is the real limit on how far back any projection can
 * be recovered.
 *
 * <p>It was previously left unset, which is not the same as undecided: an unset topic takes
 * the broker's default of seven days. That was a decision, made by accident, and it meant a
 * candle collection could be rebuilt only if the exchange was less than a week old.
 */
@Configuration
public class EventTopicConfig {

    @Bean
    public NewTopic eventTopic(@Value("${engine.partition-count}") int partitionCount,
                                 @Value(Replication.FACTOR) int replicationFactor) {
        return TopicBuilder.name(Topics.SPOT_EVENTS)
                .partitions(partitionCount)
                .replicas(replicationFactor)
                // acks=all waits for this many copies; one broker may die without a lost write.
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(Replication.minInSync(replicationFactor)))
                // -1 is Kafka's "keep forever". Note this only applies when the topic is
                // created: changing it on a topic that already exists is an operator
                // action, not something a restart will do.
                .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
                .build();
    }
}
