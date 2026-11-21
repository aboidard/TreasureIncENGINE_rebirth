package org.bogomips.treasureInc.engine.messaging;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RedisRequestStatsTest {

    @Test
    void requestsOfTheSameTenSecondsShareABucket() {
        // Maestro reads the same keys: treasure_inc.stats.requests.<epochSecond / 10>
        assertEquals("treasure_inc.stats.requests.178000000", RedisRequestStats.bucketKey(Instant.ofEpochSecond(1_780_000_000L)));
        assertEquals("treasure_inc.stats.requests.178000000", RedisRequestStats.bucketKey(Instant.ofEpochSecond(1_780_000_009L)));
        assertNotEquals(RedisRequestStats.bucketKey(Instant.ofEpochSecond(1_780_000_009L)),
                RedisRequestStats.bucketKey(Instant.ofEpochSecond(1_780_000_010L)));
    }
}
