package com.exchange.common.id;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * A ULID-shaped id: the top 48 bits are the current millisecond timestamp, the
 * remaining 80 bits are random -- so ids stay roughly time-ordered without a central
 * sequence, which is what an order/trade id's own javadoc asks for ("generate it as a
 * ULID so it stays time-ordered").
 *
 * <p>Not the canonical Crockford base32 ULID text encoding -- nothing here needs an id
 * to be human-typed, only to be a time-ordered {@link UUID} and, on the wire, 16 raw
 * bytes (matching {@code exchange/spot/v1/common.proto}'s "ids are 16 raw bytes, a
 * ULID" convention).
 */
public final class Ulid {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {
    }

    public static UUID randomUuid() {
        long timestampMs = System.currentTimeMillis() & 0xFFFF_FFFF_FFFFL; // 48 bits
        byte[] randomBytes = new byte[10]; // 80 bits
        RANDOM.nextBytes(randomBytes);

        long msb = (timestampMs << 16)
                | ((randomBytes[0] & 0xFFL) << 8)
                | (randomBytes[1] & 0xFFL);
        long lsb = ByteBuffer.wrap(randomBytes, 2, 8).getLong();

        return new UUID(msb, lsb);
    }

    /**
     * The smallest id minted at {@code at}: every id from that millisecond on sorts at or after
     * it. Turns a time range into a primary-key range on a table keyed by ULIDs.
     */
    public static UUID floor(Instant at) {
        return new UUID((at.toEpochMilli() & 0xFFFF_FFFF_FFFFL) << 16, 0L);
    }

    /** The wire form {@code bytes} proto fields expect: the UUID's 16 bytes, big-endian. */
    public static byte[] toBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    /** The reverse of {@link #toBytes}, for a consumer reading a {@code bytes} id field
     *  back off the wire. */
    public static UUID toUuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
