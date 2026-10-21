package org.bogomips.treasureInc.engine.user;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.redis.client.Command;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.bogomips.treasureInc.engine.user.UserService.LockTimeoutException;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Redis lock: {@code SET key token NX PX ttl}, released only by its owner (compare-and-delete script).
 * The ttl is a safety net so a crashed instance cannot block a user forever; it must exceed the longest
 * critical section.
 */
@ApplicationScoped
public class RedisUserLock implements UserService.Lock {

    private static final Logger LOG = Logger.getLogger(RedisUserLock.class);

    static final String KEY_PREFIX = "treasure_inc.locks.users.";
    private static final String RELEASE_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
    private static final long RETRY_DELAY_MS = 25;

    private final ReactiveRedisDataSource redis;
    private final Vertx vertx;
    private final Duration ttl;
    private final Duration maxWait;

    public RedisUserLock(ReactiveRedisDataSource redis, Vertx vertx,
                         @ConfigProperty(name = "engine.locks.ttl", defaultValue = "10s") Duration ttl,
                         @ConfigProperty(name = "engine.locks.max-wait", defaultValue = "5s") Duration maxWait) {
        this.redis = redis;
        this.vertx = vertx;
        this.ttl = ttl;
        this.maxWait = maxWait;
    }

    @Override
    public <T> Uni<T> withLock(String publicKey, Supplier<Uni<T>> action) {
        String key = KEY_PREFIX + publicKey;
        String token = UUID.randomUUID().toString();
        return acquire(publicKey, key, token, System.nanoTime() + maxWait.toNanos())
                .flatMap(v -> Uni.createFrom().<T>deferred(action::get).eventually(() -> release(key, token)));
    }

    private Uni<Void> acquire(String publicKey, String key, String token, long deadlineNanos) {
        return redis.execute(Command.SET, key, token, "NX", "PX", String.valueOf(ttl.toMillis())).flatMap(reply -> {
            if (reply != null) {
                return Uni.createFrom().voidItem();
            }
            if (System.nanoTime() > deadlineNanos) {
                LOG.warnf("event=lock_timeout user=%s maxWait=%s", publicKey, maxWait);
                return Uni.createFrom().failure(new LockTimeoutException(publicKey));
            }
            // Vert.x timer rather than a Mutiny delay: we must resume on the caller's Vert.x context
            return Uni.createFrom().<Void>emitter(e -> vertx.setTimer(RETRY_DELAY_MS, id -> e.complete(null)))
                    .flatMap(v -> acquire(publicKey, key, token, deadlineNanos));
        });
    }

    private Uni<Void> release(String key, String token) {
        return redis.execute(Command.EVAL, RELEASE_SCRIPT, "1", key, token)
                .replaceWithVoid()
                .onFailure().recoverWithItem(t -> {
                    LOG.warnf("event=lock_release_failed key=%s reason=%s (it will expire)", key, t.toString());
                    return null;
                });
    }
}
