package com.exchange.perf.tools;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.CancelAllOrders;
import com.exchange.contracts.spot.v1.Command;
import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Cancels every order the engine benchmarks ever rested. They trade on markets of their own
 * (symbols beginning {@code PERF}), straight onto the command log, as user 1; a run that was
 * interrupted, or an older version that did not clean up, leaves those ladders in every engine
 * that restores the next snapshot -- including the stack's own -- and each engine start then
 * carries and snapshots a book of a million dead orders. This reads the command log once for
 * the symbols and sends one {@code CancelAllOrders} per symbol; the running engine does the
 * rest and the next snapshot is clean.
 *
 * <p>{@code ./gradlew perfClean}. Harmless to run twice.
 */
public final class ClearBenchmarkOrders {

    private ClearBenchmarkOrders() {
    }

    public static void main(String[] args) {
        String bootstrap = args.length > 0 ? args[0] : "localhost:9092";
        TreeSet<String> symbols = new TreeSet<>();
        try (KafkaConsumer<String, byte[]> reader = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, "perf-clean-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5000))) {
            reader.subscribe(List.of(Topics.SPOT_COMMANDS));
            int idle = 0;
            long scanned = 0;
            while (idle < 10) {
                ConsumerRecords<String, byte[]> records = reader.poll(Duration.ofMillis(500));
                if (records.isEmpty()) {
                    idle++;
                    continue;
                }
                idle = 0;
                for (ConsumerRecord<String, byte[]> r : records) {
                    scanned++;
                    String symbol = r.key();
                    if (symbol != null && symbol.startsWith("PERF")) symbols.add(symbol);
                }
            }
            System.out.printf("scanned %,d commands; %d benchmark markets%n", scanned, symbols.size());
        }
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            for (String symbol : symbols) {
                producer.send(new ProducerRecord<>(Topics.SPOT_COMMANDS, symbol, cancelAll(symbol)));
            }
            producer.flush();
        }
        System.out.println("sent a cancel-all for each; the running engine clears them and the next snapshot is clean");
    }

    /** The same shape the benchmarks use at the end of every run. */
    public static byte[] cancelAll(String symbol) {
        UUID id = UUID.randomUUID();
        return Command.newBuilder()
                .setCommandId(ByteString.copyFrom(ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array()))
                .setSymbol(symbol)
                .setTimestampUs(System.currentTimeMillis() * 1_000)
                .setCancelAllOrders(CancelAllOrders.newBuilder().setUserId(1))
                .build().toByteArray();
    }
}
