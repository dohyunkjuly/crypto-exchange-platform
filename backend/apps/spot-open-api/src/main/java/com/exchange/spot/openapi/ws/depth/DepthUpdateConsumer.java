package com.exchange.spot.openapi.ws.depth;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.DepthLevelChanged;
import com.exchange.marketdata.stream.MarketFanout;
import com.exchange.marketdata.stream.Subscription;
import com.exchange.marketdata.stream.WsMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reads {@code exchange.spot.depth.v1} and pushes each watched market's level changes as one
 * frame per 100 ms -- the usual cadence for a diff stream, fast enough that a mirrored book
 * feels live and slow enough that a busy market is not a frame per event.
 *
 * <p>Changes for a market nobody here is watching are folded and then dropped at the flush,
 * so the batcher never holds more than one window of the whole exchange. Starts at
 * {@code latest}: a client seeds from a REST snapshot, and history on this topic is worthless
 * to it.
 */
@Component
public class DepthUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(DepthUpdateConsumer.class);

    private final MarketFanout sockets;
    private final DepthUpdateBatcher batcher = new DepthUpdateBatcher();

    public DepthUpdateConsumer(MarketFanout sockets) {
        this.sockets = sockets;
    }

    @KafkaListener(topics = Topics.SPOT_DEPTH, groupId = "#{@depthUpdateConsumerGroup.id}",
            properties = {"auto.offset.reset=latest"})
    public void onChange(ConsumerRecord<String, byte[]> record) {
        DepthLevelChanged change;
        try {
            change = DepthLevelChanged.parseFrom(record.value());
        } catch (InvalidProtocolBufferException e) {
            log.error("skipping malformed depth change at partition {} offset {}", record.partition(), record.offset(), e);
            return;
        }
        synchronized (batcher) {
            batcher.add(change);
        }
    }

    @Scheduled(fixedDelayString = "${exchange.open-api.ws.depth-update-interval-ms:100}")
    public void flush() {
        List<DepthUpdateData> frames;
        synchronized (batcher) {
            frames = batcher.drain();
        }
        for (DepthUpdateData frame : frames) {
            sockets.broadcast(Subscription.of(DepthUpdateChannel.NAME, frame.symbol()),
                    WsMessage.push(DepthUpdateChannel.NAME, frame));
        }
    }
}
