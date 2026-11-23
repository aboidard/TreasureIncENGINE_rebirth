package org.bogomips.treasureInc.engine.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;

/**
 * The settings as JSON in the Redis key {@value #KEY}, with no expiry: {@link Settings#toMap}. The API reads it (and
 * works out by itself whether the maintenance is under way: "active" is only right when the key was written).
 */
@ApplicationScoped
class RedisSettingsMirror implements SettingsService.Mirror {

    static final String KEY = "treasure_inc.settings";

    private final ReactiveValueCommands<String, String> values;
    private final ObjectMapper mapper;

    RedisSettingsMirror(ReactiveRedisDataSource redis, ObjectMapper mapper) {
        this.values = redis.value(String.class, String.class);
        this.mapper = mapper;
    }

    @Override
    public Uni<Settings> read() {
        return values.get(KEY).map(json -> {
            if (json == null) {
                return null;
            }
            try {
                return Settings.fromJson(mapper.readTree(json));
            } catch (JsonProcessingException e) {
                // rewritten from the database
                return null;
            }
        });
    }

    @Override
    public Uni<Void> write(Settings settings) {
        try {
            return values.set(KEY, mapper.writeValueAsString(settings.toMap(Instant.now())));
        } catch (JsonProcessingException e) {
            return Uni.createFrom().failure(e);
        }
    }
}
