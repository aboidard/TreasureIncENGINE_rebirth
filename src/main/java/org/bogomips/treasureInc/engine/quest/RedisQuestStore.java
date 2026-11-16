package org.bogomips.treasureInc.engine.quest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.quest.QuestBoards.State;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * One key per user, "treasure_inc.quests.&lt;publicKey&gt;":
 * {"daily":{"period":"2026-11-16","quests":[{"id":"daily_expeditions","progress":2,"claimed":false}]},"weekly":{...}}
 * It expires once every board it holds is over: a player who comes back gets new ones anyway.
 */
@ApplicationScoped
class RedisQuestStore implements QuestBoards.Store {

    static final String KEY_PREFIX = "treasure_inc.quests.";

    private final ReactiveValueCommands<String, String> values;
    private final ObjectMapper mapper;
    private final Duration ttl;

    RedisQuestStore(ReactiveRedisDataSource redis, ObjectMapper mapper,
                    @ConfigProperty(name = "engine.quests.ttl", defaultValue = "8d") Duration ttl) {
        this.values = redis.value(String.class, String.class);
        this.mapper = mapper;
        this.ttl = ttl;
    }

    @Override
    public Uni<State> get(String publicKey) {
        return values.get(KEY_PREFIX + publicKey).map(json -> json == null ? null : read(publicKey, json));
    }

    @Override
    public Uni<Void> put(String publicKey, State state) {
        try {
            return values.setex(KEY_PREFIX + publicKey, ttl.toSeconds(), mapper.writeValueAsString(state));
        } catch (JsonProcessingException e) {
            return Uni.createFrom().failure(e);
        }
    }

    private State read(String publicKey, String json) {
        try {
            return mapper.readValue(json, State.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupted quests entry for " + publicKey, e);
        }
    }
}
