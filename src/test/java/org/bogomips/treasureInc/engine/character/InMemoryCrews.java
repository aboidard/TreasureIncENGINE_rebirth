package org.bogomips.treasureInc.engine.character;

import io.smallrye.mutiny.Uni;
import org.bogomips.treasureInc.engine.character.CrewStore.Crew;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** A {@link CrewStore} over maps instead of Redis and the database, for the tests of every domain. */
public final class InMemoryCrews {

    /** The characters every user starts with. */
    public static final int COUNT = 3;

    /** What the "database" holds: the equipment by character number, by public key. */
    public final Map<String, Map<Integer, Map<Integer, Integer>>> database = new HashMap<>();
    /** The copies of each character in the "database", by character number, by public key. */
    public final Map<String, Map<Integer, Integer>> copies = new HashMap<>();
    /** The experience points of each character in the "database", by character number, by public key. */
    public final Map<String, Map<Integer, Integer>> xp = new HashMap<>();
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
            public Uni<CrewStore.Roster> load(String publicKey, int count) {
                loads++;
                if (!knownPublicKey.equals(publicKey)) {
                    return Uni.createFrom().nullItem();
                }
                Map<Integer, Map<Integer, Integer>> crew = database.computeIfAbsent(publicKey, k -> new TreeMap<>());
                for (int num = 1; num <= count; num++) {
                    crew.putIfAbsent(num, Map.of());
                }
                Map<Integer, Integer> counts = copies.computeIfAbsent(publicKey, k -> new TreeMap<>());
                crew.keySet().forEach(num -> counts.putIfAbsent(num, 1));
                Map<Integer, Integer> points = xp.computeIfAbsent(publicKey, k -> new TreeMap<>());
                return Uni.createFrom().item(new CrewStore.Roster(new TreeMap<>(crew), new TreeMap<>(counts),
                        new TreeMap<>(points)));
            }

            public Uni<Void> save(int userId, CrewStore.Roster roster) {
                saves++;
                database.put(userIds.get(userId), new TreeMap<>(roster.equipment()));
                copies.put(userIds.get(userId), new TreeMap<>(roster.copies()));
                xp.put(userIds.get(userId), new TreeMap<>(roster.xp()));
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
