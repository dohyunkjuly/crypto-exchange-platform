package com.exchange.spot.matching.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.EngineHeartbeat;
import com.exchange.spot.matching.kafka.CommandConsumer;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

class EngineHeartbeatPublisherTest {

    private final CommandConsumer consumer = mock(CommandConsumer.class);
    private final ConsumerLivenessHealthIndicator liveness = mock(ConsumerLivenessHealthIndicator.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, byte[]> kafka = mock(KafkaTemplate.class);
    private final EngineHeartbeatPublisher publisher = new EngineHeartbeatPublisher(consumer, liveness, kafka, "engine-1");

    @BeforeEach
    void polling() {
        when(liveness.isPolling()).thenReturn(true);
        when(kafka.send(anyString(), anyInt(), anyString(), any())).thenReturn(new CompletableFuture<>());
    }

    @Test
    @DisplayName("one heartbeat per live partition, on that partition of the status topic")
    void announces_each_live_partition() throws Exception {
        when(consumer.livePartitions()).thenReturn(Set.of(2, 5));

        publisher.beat();

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(kafka).send(eq(Topics.SPOT_ENGINE_STATUS), eq(2), eq("2"), payload.capture());
        verify(kafka).send(eq(Topics.SPOT_ENGINE_STATUS), eq(5), eq("5"), any());
        EngineHeartbeat beat = EngineHeartbeat.parseFrom(payload.getValue());
        assertThat(beat.getPartition()).isEqualTo(2);
        assertThat(beat.getInstance()).isEqualTo("engine-1");
        assertThat(beat.getSentAtUs()).isPositive();
    }

    @Test
    @DisplayName("a partition still replaying is not announced: its book is behind the log")
    void silent_while_replaying() {
        when(consumer.livePartitions()).thenReturn(Set.of());

        publisher.beat();

        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any());
    }

    @Test
    @DisplayName("a consumer that stopped polling announces nothing, whatever its books say")
    void silent_when_the_consumer_is_stuck() {
        when(liveness.isPolling()).thenReturn(false);
        when(consumer.livePartitions()).thenReturn(Set.of(0));

        publisher.beat();

        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any());
    }
}
