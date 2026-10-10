package com.exchange.spot.copy.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

import com.exchange.contracts.spot.v1.Event;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.support.Acknowledgment;

/** A batch is committed only once it is handled; one that fails is handed back whole. */
class EventConsumerTest {

    private final CopyEventHandler handler = Mockito.mock(CopyEventHandler.class);
    private final Acknowledgment ack = Mockito.mock(Acknowledgment.class);
    private final EventConsumer consumer = new EventConsumer(handler);

    @Test
    @DisplayName("a handled batch is acknowledged, and a record that is not an event is skipped rather than fatal")
    void acknowledges() {
        consumer.onEvents(List.of(record(Event.newBuilder().setSequence(7).build().toByteArray()),
                record(new byte[] {(byte) 0xff, 0x01, 0x02})), ack);

        Mockito.verify(handler).handle(argThat(events -> events.size() == 1 && events.get(0).getSequence() == 7));
        Mockito.verify(ack).acknowledge();
    }

    @Test
    @DisplayName("a batch that fails is handed back from its first record and never acknowledged")
    void handsBack() {
        Mockito.doThrow(new IllegalStateException("database is down")).when(handler).handle(anyList());

        consumer.onEvents(List.of(record(Event.getDefaultInstance().toByteArray())), ack);

        Mockito.verify(ack).nack(eq(0), any(Duration.class));
        Mockito.verify(ack, Mockito.never()).acknowledge();
    }

    private static ConsumerRecord<String, byte[]> record(byte[] value) {
        return new ConsumerRecord<>("exchange.spot.events.v1", 0, 0L, "BTCUSDT", value);
    }
}
