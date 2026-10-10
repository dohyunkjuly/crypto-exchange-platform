package com.exchange.spot.matching.snapshot;

import com.exchange.contracts.spot.v1.BookSnapshot;
import com.exchange.contracts.spot.v1.SnapshotPointer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Reads and writes engine snapshots: the actual bytes go to object storage, and a small
 * pointer to them goes on a compacted Kafka topic.
 *
 * <p><b>Why not put the bytes on Kafka.</b> A snapshot covers a whole partition's book, and
 * a Kafka record tops out at 1 MB -- around 16,000 resting orders, which any real market
 * blows past easily. Raising the cap just moves the wall further away. Object storage has
 * no such limit, and the record on Kafka shrinks to a few dozen bytes.
 *
 * <p>Kafka still does what it's good at: the pointer goes to <b>the same partition
 * number</b> as the commands it describes, so recovering command partition 3 means reading
 * the last record of pointer partition 3 -- one read, no scanning, no lookup. Compaction
 * keeps that topic small.
 *
 * <p><b>Writing never blocks the caller.</b> {@link #write} hands the finished snapshot to
 * a background thread and returns immediately. That matters because the caller is the one
 * thread that matches every order, and an upload takes tens of milliseconds against a
 * match's microseconds. Handing it off is safe because {@link BookSnapshot} is immutable
 * once built -- building it has to happen on the matching thread, since it reads the live
 * book, but shipping the finished bytes doesn't.
 */
@Component
public class SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);

    public static final String TOPIC = "exchange.spot.snapshot-pointers.v1";

    private final KafkaTemplate<String, byte[]> kafka;
    private final String bootstrapServers;
    private final S3Client s3;
    private final String bucket;
    private final String endpoint;

    /**
     * Whether storage being unreachable at STARTUP should stop the engine from booting.
     *
     * <p>True by default. Storage failing while the engine is already running is usually
     * a passing outage, and it would be a bad trade to stop an exchange over something
     * that's just an optimisation -- so that case stays a warning. But storage that's
     * unreachable the moment the process starts is almost always a config mistake, and a
     * config mistake doesn't fix itself: left alone, the engine would match orders
     * perfectly, save nothing, and look completely healthy right up until a restart had
     * to replay the entire log from the beginning.
     */
    private final boolean required;    

    /**
     * One thread, so snapshots reach storage in the order they were taken and the newest is
     * always the one left standing. A pool could race and leave an older snapshot as the
     * final state. Daemon, so a slow upload cannot hold up shutdown.
     */
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "snapshot-writer");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * The snapshot waiting to be written, per partition. A map rather than the executor's
     * own queue because only the newest snapshot per partition is worth anything: if
     * storage stalls, a queue would accumulate multi-megabyte snapshots until the heap gave
     * out, whereas this replaces the pending one and drops the stale.
     */
    private final Map<Integer, BookSnapshot> pending = new ConcurrentHashMap<>();

    /**
     * The object key currently live for each partition, so the one it replaces can be
     * deleted. Without this, every snapshot ever taken would sit in the bucket forever,
     * since {@link #keyFor} deliberately gives each one a unique name. Only ever touched
     * from {@link #drain}, which always runs on the one writer thread, so a plain map
     * (no locking) is enough.
     *
     * <p>Left empty until a partition's first snapshot in this process, rather than filled
     * in at startup: a fresh process doesn't yet know what, if anything, is already in the
     * bucket from before it started, and checking costs a Kafka round trip -- one this map
     * makes sure only happens once per partition, not on every snapshot.
     */
    private final Map<Integer, String> currentKey = new java.util.HashMap<>();

    public SnapshotStore(KafkaTemplate<String, byte[]> kafka,
                         @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                         @Value("${engine.snapshot-storage.bucket}") String bucket,
                         @Value("${engine.snapshot-storage.region}") String region,
                         @Value("${engine.snapshot-storage.endpoint:}") String endpoint,
                         @Value("${engine.snapshot-storage.access-key:}") String accessKey,
                         @Value("${engine.snapshot-storage.secret-key:}") String secretKey,
                         @Value("${engine.snapshot-storage.required:true}") boolean required) {
        this.kafka = kafka;
        this.bootstrapServers = bootstrapServers;
        this.bucket = bucket;
        this.endpoint = endpoint;
        this.required = required;
        this.s3 = buildClient(region, endpoint, accessKey, secretKey);
    }

    private static S3Client buildClient(String region, String endpoint,
                                        String accessKey, String secretKey) {
        var builder = S3Client.builder().region(Region.of(region));

        // Static credentials are the MinIO path. Left blank -- which is what a deployed
        // engine does -- the default chain finds the instance role, and no secret is
        // written down anywhere.
        builder.credentialsProvider(accessKey.isBlank()
                ? DefaultCredentialsProvider.create()
                : StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)));

        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
            // MinIO addresses buckets by path, not by subdomain. Real S3 accepts both.
            builder.serviceConfiguration(
                    S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    /**
     * Proves the bucket is reachable before the engine starts matching, and creates it if
     * it is merely missing -- the same convenience the local Kafka's auto-create-topics
     * gives. In a deployed environment the bucket is made by whatever manages the
     * infrastructure and this is a HEAD that succeeds.
     *
     * <p>An unreachable bucket here stops startup unless {@link #required} says otherwise.
     * See that field for why this differs from the runtime path.
     */
    @PostConstruct
    void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return;
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                unreachable(e);
                return;
            }
        } catch (Exception e) {
            unreachable(e);
            return;
        }
        // 404: the endpoint answered, there is simply no bucket yet.
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("created snapshot bucket {}", bucket);
        } catch (Exception e) {
            unreachable(e);
        }
    }

    /** Throws when snapshots are required, warns when they are explicitly optional. */
    private void unreachable(Exception cause) {
        String where = endpoint.isBlank() ? "AWS S3 (no endpoint override)" : endpoint;
        if (!required) {
            log.warn("snapshot storage {} at {} is unreachable, and snapshots are configured "
                            + "as optional; the engine will match orders but save nothing, "
                            + "so every restart replays the whole command log",
                    bucket, where, cause);
            return;
        }
        throw new IllegalStateException(
                "snapshot storage is unreachable at startup: bucket=" + bucket
                        + " endpoint=" + where + ". Refused to start rather than just warning, "
                        + "because this is almost always a config mistake, and one that "
                        + "wouldn't be visible: the engine would run, match correctly, save no "
                        + "snapshots, and look healthy right up until a restart had to replay "
                        + "the whole log. Check SNAPSHOT_BUCKET, SNAPSHOT_S3_ENDPOINT (leave "
                        + "empty for real S3), and the credentials. To start without snapshots "
                        + "on purpose, set engine.snapshot-storage.required=false.", cause);
    }

    /**
     * Queues a snapshot for storage and returns immediately.
     *
     * <p>Returning without waiting is the point. The caller is the matching thread, and the
     * work from here is an upload plus a produce -- tens of milliseconds during which no
     * order would be matched, for something nothing downstream is waiting on.
     */
    public void write(BookSnapshot snapshot) {
        pending.put(snapshot.getPartition(), snapshot);
        writer.execute(() -> drain(snapshot.getPartition()));
    }

    private void drain(int partition) {
        BookSnapshot snapshot = pending.remove(partition);
        if (snapshot == null) {
            // An earlier task already took this partition's newest snapshot. Writing an
            // older one now would move the pointer backwards.
            return;
        }
        String key = keyFor(partition, snapshot.getOffset());
        try {
            // Read before writing anything. Once the new pointer is published below, the
            // pointer topic's own latest record IS this snapshot -- asking afterwards would
            // hand back what was just written instead of what it replaced.
            String previousKey = previousKeyFor(partition);

            byte[] body = snapshot.toByteArray();
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromBytes(body));

            // Only now, and never the other way round. A pointer published before its
            // object is durable names a snapshot that looks valid and cannot be read.
            SnapshotPointer pointer = SnapshotPointer.newBuilder()
                    .setPartition(partition)
                    .setOffset(snapshot.getOffset())
                    .setTimestampUs(snapshot.getTimestampUs())
                    .setKey(key)
                    .setSizeBytes(body.length)
                    .build();
            kafka.send(new ProducerRecord<>(TOPIC, partition,
                    String.valueOf(partition), pointer.toByteArray())).get();

            log.info("snapshot written for partition {} at offset {} ({} symbols, {} bytes, {})",
                    partition, snapshot.getOffset(), snapshot.getSymbolsCount(),
                    body.length, key);

            currentKey.put(partition, key);
            deleteSuperseded(partition, previousKey, key);

        } catch (InterruptedException e) {
            // This is the snapshot writer's own thread, not the matching thread, so the
            // flag stops only this work.
            Thread.currentThread().interrupt();
            log.warn("interrupted writing a snapshot for partition {}", partition, e);
        } catch (Exception e) {
            // Never fatal. The engine keeps running; the next restart just replays further.
            log.warn("could not write snapshot for partition {} to {}", partition, key, e);
        }
    }

    /** What is live for this partition right now, before the new snapshot replaces it. */
    private String previousKeyFor(int partition) {
        if (currentKey.containsKey(partition)) {
            return currentKey.get(partition);
        }
        return latestPointer(partition).map(SnapshotPointer::getKey).orElse(null);
    }

    /**
     * Removes the object a new snapshot just replaced.
     *
     * <p>Safe every time, not just usually: by the time this runs, the new pointer is
     * already durable on the compacted topic, so the old object can never be reached
     * through {@link #latestFor} again -- nothing will ever ask for it. A generic
     * "delete anything older than N days" bucket rule can't make that same promise,
     * because it has no way to tell "this one's been replaced" apart from "this is just
     * an old snapshot a quiet partition is still relying on."
     */
    private void deleteSuperseded(int partition, String previousKey, String justWrittenKey) {
        if (previousKey == null || previousKey.equals(justWrittenKey)) {
            return;
        }
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket).key(previousKey).build());
            log.info("removed superseded snapshot {} for partition {}", previousKey, partition);
        } catch (Exception e) {
            // Not urgent: the object is already unreachable, so this is a storage-cost
            // cleanup, not a correctness one. The next snapshot for this partition retries.
            log.warn("could not remove superseded snapshot {} for partition {}",
                    previousKey, partition, e);
        }
    }

    /**
     * Gives a queued snapshot a chance to land before the process goes away.
     *
     * <p>Bounded, because a shutdown that hangs on storage is worse than a restart that
     * replays a few thousand more commands.
     */
    @PreDestroy
    void flush() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("snapshot writer still busy at shutdown; "
                        + "the last snapshot may not have landed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The newest snapshot for a command partition, if there is one.
     *
     * <p>Two steps: read the last pointer record for this partition, then fetch what it
     * names. The pointer read seeks to the end and steps back one rather than scanning --
     * the topic is partitioned by partition number, so the last record IS the newest.
     */
    public Optional<BookSnapshot> latestFor(int partition) {
        Optional<SnapshotPointer> pointer = latestPointer(partition);
        if (pointer.isEmpty()) {
            return Optional.empty();
        }
        SnapshotPointer found = pointer.get();
        try {
            byte[] body = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket).key(found.getKey()).build()).asByteArray();

            if (body.length != found.getSizeBytes()) {
                // A short read that still parses would restore a partial book and look
                // like it worked, which is worse than replaying from the start.
                log.warn("snapshot {} is {} bytes, pointer says {}; replaying instead",
                        found.getKey(), body.length, found.getSizeBytes());
                return Optional.empty();
            }
            return Optional.of(BookSnapshot.parseFrom(body));

        } catch (Exception e) {
            log.warn("could not read snapshot {} for partition {}; replaying from the start",
                    found.getKey(), partition, e);
            return Optional.empty();
        }
    }

    private Optional<SnapshotPointer> latestPointer(int partition) {
        TopicPartition topicPartition = new TopicPartition(TOPIC, partition);
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "snapshot-reader-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                // Fail fast. Asking for offsets on a partition that does not exist does not
                // error, it waits -- and at the 60s default, one misconfigured topic turns
                // startup into eight minutes of silence before the first order is matched.
                ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000"))) {

            consumer.assign(List.of(topicPartition));
            long end = consumer.endOffsets(List.of(topicPartition)).get(topicPartition);
            long start = consumer.beginningOffsets(List.of(topicPartition)).get(topicPartition);
            if (end <= start) {
                return Optional.empty();
            }

            consumer.seek(topicPartition, end - 1);
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofSeconds(10));
            SnapshotPointer newest = null;
            for (ConsumerRecord<String, byte[]> record : records) {
                newest = SnapshotPointer.parseFrom(record.value());
            }
            return Optional.ofNullable(newest);

        } catch (Exception e) {
            // A pointer that cannot be read is not a reason to refuse to start -- the
            // command log is still the source of truth, and a full replay still works.
            log.warn("could not read a snapshot pointer for partition {}; "
                    + "replaying from the start", partition, e);
            return Optional.empty();
        }
    }

    /**
     * Derived from partition and offset, never random, so an operator can find the object
     * for a given offset by hand. Zero-padded because lexical order is the only order an
     * object listing gives you.
     */
    static String keyFor(int partition, long offset) {
        return String.format("partition=%d/offset=%019d.pb", partition, offset);
    }
}
