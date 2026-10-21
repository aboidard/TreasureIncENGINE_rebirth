package org.bogomips.treasureInc.engine.user;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.hash.ReactiveHashCommands;
import io.quarkus.redis.datasource.ScanArgs;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.user.UserService.CachedSession;

@ApplicationScoped
public class RedisUserCache implements UserService.Cache {

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
        return hash.hget(KEY, publicKey).map(json -> json == null ? null : read(publicKey, json));
    }

    @Override
    public Multi<CachedSession> scan() {
        return hash.hscan(KEY, new ScanArgs().count(SCAN_BATCH)).toMulti().map(e -> read(e.getKey(), e.getValue()));
    }

    @Override
    public Uni<Void> evict(String publicKey) {
        return hash.hdel(KEY, publicKey).replaceWithVoid();
    }

    @Override
    public Uni<Void> put(String publicKey, CachedSession session) {
        return write(session).flatMap(json -> hash.hset(KEY, publicKey, json)).replaceWithVoid();
    }

    private CachedSession read(String publicKey, String json) {
        try {
            return mapper.readValue(json, CachedSession.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupted cache entry for " + publicKey, e);
        }
    }

    private Uni<String> write(CachedSession session) {
        try {
            return Uni.createFrom().item(mapper.writeValueAsString(session));
        } catch (JsonProcessingException e) {
            return Uni.createFrom().failure(e);
        }
    }
}
