package org.bogomips.treasureInc.engine.item;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.list.ReactiveListCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.bogomips.treasureInc.engine.item.ItemService.ItemList;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * One key per user ("treasure_inc.items.&lt;publicKey&gt;") with a TTL, so lists requested through the
 * public API by users who never logged in cannot grow Redis without bound.
 */
@ApplicationScoped
class RedisItemCache implements ItemService.Cache {

    static final String KEY_PREFIX = "treasure_inc.items.";
    // ids reserved from the database sequence and not used yet, shared by every engine instance
    static final String ID_POOL_KEY = "treasure_inc.items_id_pool";

    private final ReactiveValueCommands<String, String> values;
    private final ReactiveKeyCommands<String> keys;
    private final ReactiveListCommands<String, String> idPool;
    private final ObjectMapper mapper;
    private final long ttlSeconds;
    private final long unknownUserTtlSeconds;

    RedisItemCache(ReactiveRedisDataSource redis, ObjectMapper mapper,
                          @ConfigProperty(name = "engine.items.cache-ttl-seconds", defaultValue = "300") long ttlSeconds,
                          @ConfigProperty(name = "engine.items.unknown-user-ttl-seconds", defaultValue = "60") long unknownUserTtlSeconds) {
        this.values = redis.value(String.class, String.class);
        this.keys = redis.key(String.class);
        this.idPool = redis.list(String.class, String.class);
        this.mapper = mapper;
        this.ttlSeconds = ttlSeconds;
        this.unknownUserTtlSeconds = unknownUserTtlSeconds;
    }

    @Override
    public Uni<ItemList> get(String publicKey) {
        return values.get(key(publicKey)).map(json -> json == null ? null : read(publicKey, json));
    }

    @Override
    public Uni<Void> put(String publicKey, ItemList list) {
        long ttl = list.userFound() ? ttlSeconds : unknownUserTtlSeconds;
        return write(list).flatMap(json -> values.setex(key(publicKey), ttl, json));
    }

    @Override
    public Uni<Void> persist(String publicKey, ItemList list) {
        return write(list).flatMap(json -> values.set(key(publicKey), json));
    }

    @Override
    public Uni<Void> evict(String publicKey) {
        return keys.del(key(publicKey)).replaceWithVoid();
    }

    @Override
    public Uni<List<Integer>> takeIds(int count) {
        return idPool.lpop(ID_POOL_KEY, count)
                .map(ids -> ids == null ? List.<Integer>of() : ids.stream().map(Integer::valueOf).toList());
    }

    @Override
    public Uni<Void> addIds(List<Integer> ids) {
        return idPool.rpush(ID_POOL_KEY, ids.stream().map(String::valueOf).toArray(String[]::new)).replaceWithVoid();
    }

    @Override
    public Uni<Void> removeIds(String publicKey, Collection<Integer> ids) {
        HashSet<Integer> toRemove = new HashSet<>(ids);
        return get(publicKey).flatMap(list -> {
            if (list == null || !list.userFound()) {
                return Uni.createFrom().voidItem();
            }
            ItemList remaining = new ItemList(true,
                    list.items().stream().filter(i -> !toRemove.contains(i.id())).toList(),
                    list.pendingIds().stream().filter(id -> !toRemove.contains(id)).collect(Collectors.toSet()));
            // KEEPTTL: do not make a persistent list (one holding pending items) expire
            return write(remaining).flatMap(json -> values.set(key(publicKey), json, new SetArgs().keepttl()));
        });
    }

    private static String key(String publicKey) {
        return KEY_PREFIX + publicKey;
    }

    private ItemList read(String publicKey, String json) {
        try {
            return mapper.readValue(json, ItemList.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupted items cache entry for " + publicKey, e);
        }
    }

    private Uni<String> write(ItemList list) {
        try {
            return Uni.createFrom().item(mapper.writeValueAsString(list));
        } catch (JsonProcessingException e) {
            return Uni.createFrom().failure(e);
        }
    }
}
