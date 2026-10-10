package com.exchange.common.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UlidTest {

    @Test
    void toBytesThenToUuid_roundTrips() {
        UUID original = Ulid.randomUuid();

        UUID roundTripped = Ulid.toUuid(Ulid.toBytes(original));

        assertEquals(original, roundTripped);
    }

    @Test
    void randomUuid_generatesDistinctValues() {
        assertNotEquals(Ulid.randomUuid(), Ulid.randomUuid());
    }

    @Test
    void toBytes_isSixteenBytes() {
        assertEquals(16, Ulid.toBytes(Ulid.randomUuid()).length);
    }

    /** Compared as MySQL compares BINARY(16): unsigned bytes, left to right. */
    @Test
    void floor_boundsTheIdsOfItsMillisecond() {
        Instant before = Instant.now();
        UUID id = Ulid.randomUuid();
        Instant after = Instant.now().plusMillis(1);

        assertTrue(Arrays.compareUnsigned(Ulid.toBytes(Ulid.floor(before)), Ulid.toBytes(id)) <= 0);
        assertTrue(Arrays.compareUnsigned(Ulid.toBytes(id), Ulid.toBytes(Ulid.floor(after))) < 0);
    }
}
