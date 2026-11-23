package org.bogomips.treasureInc.engine.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The events as a JSON list in the Redis key {@value #KEY}, with no expiry ({@link GameEvent#toMap}); and, per player,
 * event and day, how many expeditions were sent there: "treasure_inc.event_runs.&lt;publicKey&gt;.&lt;code&gt;.&lt;day&gt;",
 * which expires two days later.
 */
@ApplicationScoped
class RedisEventStore implements EventService.Mirror, EventService.Runs {

    static final String KEY = "treasure_inc.events";
    static final String RUNS_PREFIX = "treasure_inc.event_runs.";
    private static final Duration RUNS_TTL = Duration.ofDays(2);

    private final ReactiveValueCommands<String, String> values;
    private final ReactiveValueCommands<String, Long> counters;
    private final ReactiveKeyCommands<String> keys;
    private final ObjectMapper mapper;

    RedisEventStore(ReactiveRedisDataSource redis, ObjectMapper mapper) {
        this.values = redis.value(String.class, String.class);
        this.counters = redis.value(String.class, Long.class);
        this.keys = redis.key(String.class);
        this.mapper = mapper;
    }

    @Override
    public Uni<List<GameEvent>> read() {
        return values.get(KEY).map(json -> {
            if (json == null) {
                return null;
            }
            try {
                JsonNode list = mapper.readTree(json);
                if (!list.isArray()) {
                    return null;
                }
                List<GameEvent> events = new ArrayList<>();
                list.forEach(node -> {
                    GameEvent e = GameEvent.fromJson(node);
                    if (e != null) {
                        events.add(e);
                    }
                });
                return events;
            } catch (JsonProcessingException e) {
                // rewritten from the database
                return null;
            }
        });
    }

    @Override
    public Uni<Void> write(List<GameEvent> events) {
        Instant now = Instant.now();
        try {
            return values.set(KEY, mapper.writeValueAsString(events.stream().map(e -> e.toMap(now)).toList()));
        } catch (JsonProcessingException e) {
            return Uni.createFrom().failure(e);
        }
    }

    @Override
    public Uni<Integer> count(String publicKey, String code, LocalDate day) {
        return counters.get(runsKey(publicKey, code, day)).map(n -> n == null ? 0 : n.intValue());
    }

    @Override
    public Uni<Integer> add(String publicKey, String code, LocalDate day) {
        String key = runsKey(publicKey, code, day);
        return counters.incr(key).flatMap(n -> keys.expire(key, RUNS_TTL).replaceWith(n.intValue()));
    }

    static String runsKey(String publicKey, String code, LocalDate day) {
        return RUNS_PREFIX + publicKey + "." + code + "." + day;
    }
}
