package org.bogomips.treasureInc.engine.item.infrastructure;

import org.bogomips.treasureInc.engine.item.application.ItemCache;
import org.bogomips.treasureInc.engine.item.domain.ItemList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Collection;
import java.util.HashSet;

/**
 * One key per user ("treasure_inc.items.&lt;publicKey&gt;") with a TTL, so lists requested through the
 * public API by users who never logged in cannot grow Redis without bound.
 */
@ApplicationScoped
public class RedisItemCache implements ItemCache {

    static final String KEY_PREFIX = "treasure_inc.items.";
    static final String ID_SEQUENCE_KEY = "treasure_inc.items_seq.redis_only";

    private final ReactiveValueCommands<String, String> values;
    private final io.quarkus.redis.datasource.keys.ReactiveKeyCommands<String> keys;
    private final ObjectMapper mapper;
    private final long ttlSeconds;
    private final long unknownUserTtlSeconds;

    public RedisItemCache(ReactiveRedisDataSource redis, ObjectMapper mapper,
                          @ConfigProperty(name = "engine.items.cache-ttl-seconds", defaultValue = "300") long ttlSeconds,
                          @ConfigProperty(name = "engine.items.unknown-user-ttl-seconds", defaultValue = "60") long unknownUserTtlSeconds) {
        this.values = redis.value(String.class, String.class);
        this.keys = redis.key(String.class);
        this.mapper = mapper;
        this.ttlSeconds = ttlSeconds;
        this.unknownUserTtlSeconds = unknownUserTtlSeconds;
    }

    @Override
    public Uni<ItemList> get(String publicKey) {
        return values.get(KEY_PREFIX + publicKey).map(json -> {
            if (json == null) {
                return null;
            }
            try {
                return mapper.readValue(json, ItemList.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Corrupted items cache entry for " + publicKey, e);
            }
        });
    }

    @Override
    public Uni<Void> put(String publicKey, ItemList list) {
        try {
            return values.setex(KEY_PREFIX + publicKey, list.userFound() ? ttlSeconds : unknownUserTtlSeconds,
                    mapper.writeValueAsString(list));
        } catch (JsonProcessingException e) {
            return Uni.createFrom().failure(e);
        }
    }

    @Override
    public Uni<Void> persist(String publicKey, ItemList list) {
        try {
            return values.set(KEY_PREFIX + publicKey, mapper.writeValueAsString(list));
        } catch (JsonProcessingException e) {
            return Uni.createFrom().failure(e);
        }
    }

    @Override
    public Uni<Void> evict(String publicKey) {
        return keys.del(KEY_PREFIX + publicKey).replaceWithVoid();
    }

    @Override
    public Uni<Integer> reserveIds(int count) {
        return values.decrby(ID_SEQUENCE_KEY, count).map(Long::intValue);
    }

    @Override
    public Uni<Void> removeIds(String publicKey, Collection<Integer> ids) {
        HashSet<Integer> toRemove = new HashSet<>(ids);
        return get(publicKey).flatMap(list -> {
            if (list == null || !list.userFound()) {
                return Uni.createFrom().voidItem();
            }
            ItemList remaining = new ItemList(true, list.items().stream().filter(i -> !toRemove.contains(i.id())).toList());
            try {
                // KEEPTTL: do not make a persistent (Redis-only) list expire
                return values.set(KEY_PREFIX + publicKey, mapper.writeValueAsString(remaining), new SetArgs().keepttl());
            } catch (JsonProcessingException e) {
                return Uni.createFrom().failure(e);
            }
        });
    }
}
