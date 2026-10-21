package org.bogomips.treasureInc.engine.user;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.hash.ReactiveHashCommands;
import io.quarkus.redis.datasource.ScanArgs;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class RedisUserCache implements UserCache {

    static final String KEY = "treasure_inc.users.logged";
    private static final int SCAN_BATCH = 200;

    private final ReactiveHashCommands<String, String, String> hash;
    private final ObjectMapper mapper;

    public RedisUserCache(ReactiveRedisDataSource redis, ObjectMapper mapper) {
        this.hash = redis.hash(String.class, String.class, String.class);
        this.mapper = mapper;
    }

    @Override
    public Uni<CachedSession> get(String publicKey) {
        return hash.hget(KEY, publicKey).map(json -> {
            if (json == null) {
                return null;
            }
            try {
                return mapper.readValue(json, CachedSession.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Corrupted cache entry for " + publicKey, e);
            }
        });
    }

    @Override
    public Multi<CachedSession> scan() {
        return hash.hscan(KEY, new ScanArgs().count(SCAN_BATCH)).toMulti().map(e -> {
            try {
                return mapper.readValue(e.getValue(), CachedSession.class);
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Corrupted cache entry for " + e.getKey(), ex);
            }
        });
    }

    @Override
    public Uni<Void> evict(String publicKey) {
        return hash.hdel(KEY, publicKey).replaceWithVoid();
    }

    @Override
    public Uni<Void> put(String publicKey, CachedSession session) {
        try {
            return hash.hset(KEY, publicKey, mapper.writeValueAsString(session)).replaceWithVoid();
        } catch (JsonProcessingException e) {
            return Uni.createFrom().failure(e);
        }
    }
}
