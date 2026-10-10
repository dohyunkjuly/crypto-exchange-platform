package com.exchange.spot.copy.kafka;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.Event;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * {@code exchange.spot.events.v1}, a batch at a time (docs/reference/delivery-guarantees.md).
 *
 * <p>The offset is committed only after the whole batch is handled. A batch that fails is
 * handed back with {@code nack}, which rewinds the consumer to its first record: returning
 * without acknowledging would not, and the next batch's commit would step over this one.
 * Everything downstream is safe to see twice.
 *
 * <p>A record that cannot be parsed is logged and skipped: no retry can fix it, and it must
 * not hold up everything behind it.
 */
@Component
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    /** Long enough for a database or a broker to come back; a failing batch is retried forever. */
    private static final Duration RETRY_AFTER = Duration.ofSeconds(2);

    private final CopyEventHandler handler;

    public EventConsumer(CopyEventHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(topics = Topics.SPOT_EVENTS)
    public void onEvents(List<ConsumerRecord<String, byte[]>> records, Acknowledgment ack) {
        List<Event> events = new ArrayList<>(records.size());
        for (ConsumerRecord<String, byte[]> record : records) {
            try {
                events.add(Event.parseFrom(record.value()));
            } catch (InvalidProtocolBufferException e) {
                log.error("skipping malformed event at partition {} offset {}", record.partition(), record.offset(), e);
            }
        }
        try {
            handler.handle(events);
        } catch (RuntimeException e) {
            log.error("failed to copy from a batch of {} events; handing it back to be read again",
                    records.size(), e);
            ack.nack(0, RETRY_AFTER);
            return;
        }
        ack.acknowledge();
    }
}
