package org.bogomips.treasureInc.engine.messaging;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;

/**
 * Counts the requests received by every engine instance in Redis, in buckets of {@link #BUCKET_SECONDS} seconds
 * ({@code treasure_inc.stats.requests.<epochSecond / 10>}), so Maestro can report the current load.
 * Counting is best effort: a failure is logged and never delays the request.
 */
@ApplicationScoped
class RedisRequestStats {

    private static final Logger LOG = Logger.getLogger(RedisRequestStats.class);

    static final String KEY_PREFIX = "treasure_inc.stats.requests.";
    static final long BUCKET_SECONDS = 10;
    // long enough for Maestro to read the last minute, short enough to keep a handful of keys
    private static final Duration BUCKET_TTL = Duration.ofMinutes(2);

    private final ReactiveValueCommands<String, Long> values;
    private final ReactiveKeyCommands<String> keys;

    RedisRequestStats(ReactiveRedisDataSource redis) {
        this.values = redis.value(String.class, Long.class);
        this.keys = redis.key(String.class);
    }

    /** The key of the bucket holding {@code at}. */
    static String bucketKey(Instant at) {
        return KEY_PREFIX + Math.floorDiv(at.getEpochSecond(), BUCKET_SECONDS);
    }

    void record() {
        String key = bucketKey(Instant.now());
        values.incr(key)
                .flatMap(n -> n == 1 ? keys.expire(key, BUCKET_TTL) : Uni.createFrom().item(true))
                .subscribe().with(ok -> { }, t -> LOG.debugf("event=request_count_failed key=%s reason=%s", key, t.toString()));
    }
}
