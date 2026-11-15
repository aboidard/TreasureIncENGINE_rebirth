package org.bogomips.treasureInc.engine.character;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.user.User;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@ApplicationScoped
class PanacheCharacterRepository implements CrewStore.Repository {

    @Override
    public Uni<Map<Integer, Map<Integer, Integer>>> load(String publicKey, int count) {
        return Panache.withTransaction(() -> User.<User>find("publicKey", publicKey).firstResult().flatMap(user -> {
            if (user == null) {
                return Uni.createFrom().nullItem();
            }
            return ofUser(user.id).flatMap(existing -> {
                Map<Integer, Map<Integer, Integer>> crew = new TreeMap<>();
                existing.forEach(c -> crew.put(c.num, slots(c.equipment)));
                List<GameCharacter> missing = new ArrayList<>();
                for (int num = 1; num <= count; num++) {
                    if (crew.putIfAbsent(num, Map.of()) == null) {
                        missing.add(newCharacter(user.id, num, Map.of()));
                    }
                }
                Uni<Void> created = missing.isEmpty() ? Uni.createFrom().voidItem() : GameCharacter.persist(missing);
                return created.replaceWith(crew);
            });
        }));
    }

    @Override
    public Uni<Void> save(int userId, Map<Integer, Map<Integer, Integer>> equipment) {
        return Panache.withTransaction(() -> ofUser(userId).flatMap(existing -> {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            Map<Integer, Map<Integer, Integer>> left = new TreeMap<>(equipment);
            for (GameCharacter c : existing) {
                Map<Integer, Integer> worn = left.remove(c.num);
                if (worn != null) {
                    c.equipment = json(worn);
                    c.updatedAt = now;
                }
            }
            List<GameCharacter> missing = left.entrySet().stream()
                    .map(e -> newCharacter(userId, e.getKey(), e.getValue())).toList();
            return missing.isEmpty() ? Uni.createFrom().voidItem() : GameCharacter.persist(missing);
        }));
    }

    private static Uni<List<GameCharacter>> ofUser(int userId) {
        return GameCharacter.list("userId = ?1 order by num", userId);
    }

    private static GameCharacter newCharacter(int userId, int num, Map<Integer, Integer> equipment) {
        GameCharacter c = new GameCharacter();
        c.userId = userId;
        c.num = num;
        c.equipment = json(equipment);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        c.createdAt = now;
        c.updatedAt = now;
        return c;
    }

    /** JSON object keys are strings, and a number read back from JSON is not always an Integer. */
    private static Map<Integer, Integer> slots(Map<String, ?> json) {
        Map<Integer, Integer> slots = new TreeMap<>();
        if (json != null) {
            json.forEach((slot, id) -> slots.put(Integer.valueOf(slot), ((Number) id).intValue()));
        }
        return slots;
    }

    private static Map<String, Integer> json(Map<Integer, Integer> slots) {
        Map<String, Integer> json = new TreeMap<>();
        slots.forEach((slot, id) -> json.put(String.valueOf(slot), id));
        return json;
    }
}
