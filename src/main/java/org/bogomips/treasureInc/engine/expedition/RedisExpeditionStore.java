package org.bogomips.treasureInc.engine.expedition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.hash.ReactiveHashCommands;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.expedition.ExpeditionService.Expedition;

/**
 * Per user: "treasure_inc.expeditions.&lt;publicKey&gt;" holds the expeditions by id (JSON), and
 * "treasure_inc.digs.&lt;publicKey&gt;.&lt;location&gt;" the rarity of the dug cases by case id.
 * Both expire, as a player who leaves the game never opens the chests of the expeditions under way.
 */
@ApplicationScoped
class RedisExpeditionStore implements ExpeditionService.Store {

    static final String EXPEDITIONS_PREFIX = "treasure_inc.expeditions.";
    static final String DIGS_PREFIX = "treasure_inc.digs.";

    private final ReactiveHashCommands<String, String, String> hash;
    private final ReactiveKeyCommands<String> keys;
    private final ObjectMapper mapper;
    private final ExpeditionConfig config;

    RedisExpeditionStore(ReactiveRedisDataSource redis, ObjectMapper mapper, ExpeditionConfig config) {
        this.hash = redis.hash(String.class, String.class, String.class);
        this.keys = redis.key(String.class);
        this.mapper = mapper;
        this.config = config;
    }

    @Override
    public Uni<Integer> dugRarity(String publicKey, String location, int caseId) {
        return hash.hget(digsKey(publicKey, location), String.valueOf(caseId))
                .map(rarity -> rarity == null ? null : Integer.valueOf(rarity));
    }

    @Override
    public Uni<Void> putDug(String publicKey, String location, int caseId, int rarity) {
        String key = digsKey(publicKey, location);
        return hash.hset(key, String.valueOf(caseId), String.valueOf(rarity))
                .flatMap(v -> keys.expire(key, config.digTtl()))
                .replaceWithVoid();
    }

    @Override
    public Uni<Void> clearDug(String publicKey, String location) {
        return keys.del(digsKey(publicKey, location)).replaceWithVoid();
    }

    @Override
    public Uni<Expedition> get(String publicKey, String expeditionId) {
        return hash.hget(expeditionsKey(publicKey), expeditionId)
                .map(json -> json == null ? null : read(expeditionId, json));
    }

    @Override
    public Uni<Void> put(String publicKey, Expedition expedition) {
        String key = expeditionsKey(publicKey);
        return write(expedition)
                .flatMap(json -> hash.hset(key, expedition.id(), json))
                .flatMap(v -> keys.expire(key, config.ttl()))
                .replaceWithVoid();
    }

    @Override
    public Uni<Void> remove(String publicKey, String expeditionId) {
        return hash.hdel(expeditionsKey(publicKey), expeditionId).replaceWithVoid();
    }

    private static String expeditionsKey(String publicKey) {
        return EXPEDITIONS_PREFIX + publicKey;
    }

    private static String digsKey(String publicKey, String location) {
        return DIGS_PREFIX + publicKey + "." + location;
    }

    private Expedition read(String expeditionId, String json) {
        try {
            return mapper.readValue(json, Expedition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupted expedition entry " + expeditionId, e);
        }
    }

    private Uni<String> write(Expedition expedition) {
        try {
            return Uni.createFrom().item(mapper.writeValueAsString(expedition));
        } catch (JsonProcessingException e) {
            return Uni.createFrom().failure(e);
        }
    }
}
