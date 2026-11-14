package org.bogomips.treasureInc.engine.character;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.character.CrewStore.Crew;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * One key per user, "treasure_inc.characters.&lt;publicKey&gt;":
 * {"characters":{"1":{"3":640},"2":{},"3":{}},"dirty":true}
 */
@ApplicationScoped
class RedisCharacterCache implements CrewStore.Cache {

    static final String KEY_PREFIX = "treasure_inc.characters.";

    private final ReactiveValueCommands<String, String> values;
    private final ReactiveKeyCommands<String> keys;
    private final ObjectMapper mapper;
    private final long ttlSeconds;

    RedisCharacterCache(ReactiveRedisDataSource redis, ObjectMapper mapper,
                        @ConfigProperty(name = "engine.characters.cache-ttl-seconds", defaultValue = "300") long ttlSeconds) {
        this.values = redis.value(String.class, String.class);
        this.keys = redis.key(String.class);
        this.mapper = mapper;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public Uni<Crew> get(String publicKey) {
        return values.get(key(publicKey)).map(json -> json == null ? null : read(publicKey, json));
    }

    @Override
    public Uni<Void> put(String publicKey, Crew crew) {
        return write(crew).flatMap(json -> values.setex(key(publicKey), ttlSeconds, json));
    }

    @Override
    public Uni<Void> persist(String publicKey, Crew crew) {
        return write(crew).flatMap(json -> values.set(key(publicKey), json));
    }

    @Override
    public Uni<Void> evict(String publicKey) {
        return keys.del(key(publicKey)).replaceWithVoid();
    }

    private static String key(String publicKey) {
        return KEY_PREFIX + publicKey;
    }

    private Crew read(String publicKey, String json) {
        try {
            return mapper.readValue(json, Crew.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupted characters cache entry for " + publicKey, e);
        }
    }

    private Uni<String> write(Crew crew) {
        try {
            return Uni.createFrom().item(mapper.writeValueAsString(crew));
        } catch (JsonProcessingException e) {
            return Uni.createFrom().failure(e);
        }
    }
}
