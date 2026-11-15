package org.bogomips.treasureInc.engine.character;

import io.smallrye.mutiny.Uni;
import org.bogomips.treasureInc.engine.character.CrewStore.Crew;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** A {@link CrewStore} over maps instead of Redis and the database, for the tests of every domain. */
public final class InMemoryCrews {

    public static final int COUNT = 3;

    /** What the "database" holds: the equipment by character number, by public key. */
    public final Map<String, Map<Integer, Map<Integer, Integer>>> database = new HashMap<>();
    public final Map<String, Crew> cache = new HashMap<>();
    /** The user ids given to {@link CrewStore.Repository#save}, by public key through {@link #userIds}. */
    public final Map<Integer, String> userIds = new HashMap<>();
    public int loads;
    public int saves;

    private final String knownPublicKey;

    /** @param knownPublicKey the only user of the "database", whose id is {@code userId} */
    public InMemoryCrews(String knownPublicKey, int userId) {
        this.knownPublicKey = knownPublicKey;
        userIds.put(userId, knownPublicKey);
    }

    public CrewStore store() {
        return new CrewStore(new CrewStore.Repository() {
            public Uni<Map<Integer, Map<Integer, Integer>>> load(String publicKey, int count) {
                loads++;
                if (!knownPublicKey.equals(publicKey)) {
                    return Uni.createFrom().nullItem();
                }
                Map<Integer, Map<Integer, Integer>> crew = database.computeIfAbsent(publicKey, k -> new TreeMap<>());
                for (int num = 1; num <= count; num++) {
                    crew.putIfAbsent(num, Map.of());
                }
                return Uni.createFrom().item(new TreeMap<>(crew));
            }

            public Uni<Void> save(int userId, Map<Integer, Map<Integer, Integer>> equipment) {
                saves++;
                database.put(userIds.get(userId), new TreeMap<>(equipment));
                return Uni.createFrom().voidItem();
            }
        }, new CrewStore.Cache() {
            public Uni<Crew> get(String publicKey) {
                return Uni.createFrom().item(cache.get(publicKey));
            }

            public Uni<Void> put(String publicKey, Crew crew) {
                cache.put(publicKey, crew);
                return Uni.createFrom().voidItem();
            }

            public Uni<Void> persist(String publicKey, Crew crew) {
                return put(publicKey, crew);
            }

            public Uni<Void> evict(String publicKey) {
                cache.remove(publicKey);
                return Uni.createFrom().voidItem();
            }
        }, COUNT);
    }
}
