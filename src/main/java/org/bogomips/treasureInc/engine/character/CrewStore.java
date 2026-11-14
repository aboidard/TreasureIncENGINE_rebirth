package org.bogomips.treasureInc.engine.character;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.item.ItemService;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The characters of the users and what they wear, served from Redis and read from the database on a miss.
 * A change is only written to Redis; {@link #sync} writes it to the database later (see CacheSyncService).
 * Read-modify-write sequences must run inside the user's lock.
 */
@ApplicationScoped
public class CrewStore implements ItemService.Equipped {

    private final Repository repository;
    private final Cache cache;
    private final int count;

    /** @param count how many characters a user has */
    public CrewStore(Repository repository, Cache cache,
                     @ConfigProperty(name = "engine.characters.count", defaultValue = "3") int count) {
        if (count < 1) {
            throw new IllegalArgumentException("engine.characters.count must be at least 1, got " + count);
        }
        this.repository = repository;
        this.cache = cache;
        this.count = count;
    }

    /** Character numbers go from 1 to this count. */
    public int count() {
        return count;
    }

    /** The characters of the user; null when the public key is unknown. */
    public Uni<Crew> get(String publicKey) {
        return cache.get(publicKey).flatMap(cached -> {
            if (cached != null) {
                return Uni.createFrom().item(cached);
            }
            return repository.load(publicKey, count).flatMap(equipment -> {
                if (equipment == null) {
                    return Uni.createFrom().nullItem();
                }
                Crew crew = new Crew(equipment, false);
                return cache.put(publicKey, crew).replaceWith(crew);
            });
        });
    }

    /** Stores changed characters in Redis only, flagged for the next synchronization. */
    public Uni<Void> save(String publicKey, Crew crew) {
        return cache.persist(publicKey, new Crew(crew.characters(), true));
    }

    @Override
    public Uni<Set<Integer>> ids(String publicKey) {
        return get(publicKey).map(crew -> crew == null ? Set.<Integer>of() : crew.equippedIds());
    }

    /** Writes the characters to the database if they changed since the last call. */
    public Uni<Void> sync(String publicKey, int userId) {
        return cache.get(publicKey).flatMap(crew -> {
            if (crew == null || !crew.dirty()) {
                return Uni.createFrom().voidItem();
            }
            // the entry may expire again once the database holds it
            return repository.save(userId, crew.characters())
                    .flatMap(v -> cache.put(publicKey, new Crew(crew.characters(), false)));
        });
    }

    public Uni<Void> evict(String publicKey) {
        return cache.evict(publicKey);
    }

    // ---- values

    /**
     * The characters of a user as kept in Redis: for each character number, the item id by slot number.
     * dirty says whether the database is behind.
     */
    public record Crew(
            @JsonProperty("characters") Map<Integer, Map<Integer, Integer>> characters,
            @JsonProperty("dirty") boolean dirty) {

        public Set<Integer> equippedIds() {
            return characters.values().stream().flatMap(e -> e.values().stream()).collect(Collectors.toSet());
        }

        /** The item ids by slot of a character; empty when it wears nothing. */
        public Map<Integer, Integer> equipment(int num) {
            return characters.getOrDefault(num, Map.of());
        }

        /** A copy where the slot of the character holds the item; a null item empties the slot. */
        public Crew with(int num, int slot, Integer itemId) {
            Map<Integer, Integer> equipment = new TreeMap<>(equipment(num));
            if (itemId == null) {
                equipment.remove(slot);
            } else {
                equipment.put(slot, itemId);
            }
            Map<Integer, Map<Integer, Integer>> all = new TreeMap<>(characters);
            all.put(num, equipment);
            return new Crew(all, dirty);
        }
    }

    // ---- ports, implemented by the Panache and Redis adapters

    public interface Repository {
        /**
         * Item id by slot for each character number in 1..count; the characters the user does not have yet are
         * created. Null when the public key is unknown.
         */
        Uni<Map<Integer, Map<Integer, Integer>>> load(String publicKey, int count);

        /** Writes the equipment of the characters of the user, by character number. */
        Uni<Void> save(int userId, Map<Integer, Map<Integer, Integer>> equipment);
    }

    public interface Cache {
        /** Null when nothing is cached for this public key. */
        Uni<Crew> get(String publicKey);

        /** Stores the characters with an expiry: the database has them too. */
        Uni<Void> put(String publicKey, Crew crew);

        /** Stores the characters without expiry: used while Redis is the only place holding their last state. */
        Uni<Void> persist(String publicKey, Crew crew);

        Uni<Void> evict(String publicKey);
    }
}
