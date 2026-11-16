package org.bogomips.treasureInc.engine.character;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.bogomips.treasureInc.engine.character.CharacterCatalog.Info;
import org.bogomips.treasureInc.engine.character.CharacterCatalog.Rank;
import org.bogomips.treasureInc.engine.item.Equipment.Skill;
import org.bogomips.treasureInc.engine.item.ItemInfo;
import org.bogomips.treasureInc.engine.item.ItemService;
import org.bogomips.treasureInc.engine.item.ItemService.ItemList;
import org.bogomips.treasureInc.engine.item.ItemService.SellResult;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;
import org.bogomips.treasureInc.engine.sync.CacheSyncService;
import org.bogomips.treasureInc.engine.user.UserInfo;
import org.bogomips.treasureInc.engine.user.UserService;
import org.bogomips.treasureInc.engine.user.UserService.CachedSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class RecruitEndpointsTest {

    private static final String PUB = "12345678900";
    private static final String PRIV = "secret";
    private static final int USER_ID = 1;
    private static final int START_MONEY = 15000;
    private static final int COST = 1000;
    private static final int MULTI = 10;
    private static final String PORTAL = "masterpack1";

    /** Rolls of the rank: the first one of the range of each rank (see Rank.fromRoll). */
    private static final int ROLL_SSS = 0;
    private static final int ROLL_S = 10;
    private static final int ROLL_D = 999;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedSession> sessions = new HashMap<>();
    private final Map<String, ItemList> itemLists = new HashMap<>();
    private final List<ItemInfo> dbItems = List.of(
            new ItemInfo(10, "épée terne", "d", 91, 100, "common", "t", "t", null, Map.of("slot", 3, "for", 4)));

    private final UserService.Repository userRepo = new UserService.Repository() {
        public Uni<UserInfo> login(String pub, String priv) {
            return authenticate(pub, priv);
        }

        public Uni<UserInfo> authenticate(String pub, String priv) {
            return Uni.createFrom().item(PUB.equals(pub) && PRIV.equals(priv)
                    ? new UserInfo(USER_ID, pub, START_MONEY, "t", "t", "t") : null);
        }

        public Uni<Boolean> exists(String pub) {
            return Uni.createFrom().item(false);
        }

        public Uni<Void> updateMoney(int id, int money) {
            return Uni.createFrom().voidItem();
        }

        public Uni<UserInfo> create(String pub, String priv, int money) {
            throw new UnsupportedOperationException();
        }
    };

    private final UserService.Cache userCache = new UserService.Cache() {
        public Uni<CachedSession> get(String k) {
            return Uni.createFrom().item(sessions.get(k));
        }

        public Uni<Void> evict(String k) {
            sessions.remove(k);
            return Uni.createFrom().voidItem();
        }

        public Multi<CachedSession> scan() {
            return Multi.createFrom().iterable(List.copyOf(sessions.values()));
        }

        public Uni<Void> put(String k, CachedSession s) {
            sessions.put(k, s);
            return Uni.createFrom().voidItem();
        }
    };

    private final UserService.Lock lock = new UserService.Lock() {
        public <T> Uni<T> withLock(String publicKey, Supplier<Uni<T>> action) {
            return Uni.createFrom().deferred(action::get);
        }
    };

    private final ItemService.Repository itemRepo = new ItemService.Repository() {
        public Uni<ItemList> findByPublicKey(String publicKey) {
            return Uni.createFrom().item(PUB.equals(publicKey) ? new ItemList(true, dbItems) : ItemList.unknownUser());
        }

        public Uni<SellResult> sell(String publicKey, String privateKey, List<Integer> ids, int extraMoney) {
            throw new UnsupportedOperationException();
        }

        public Uni<List<Integer>> nextIds(int count) {
            throw new UnsupportedOperationException();
        }

        public Uni<Void> insertAll(int userId, List<ItemInfo> items) {
            throw new UnsupportedOperationException();
        }

        public Uni<Void> deleteByIds(int userId, List<Integer> ids) {
            return Uni.createFrom().voidItem();
        }
    };

    private final ItemService.Cache itemCache = new ItemService.Cache() {
        public Uni<ItemList> get(String publicKey) {
            return Uni.createFrom().item(itemLists.get(publicKey));
        }

        public Uni<Void> put(String publicKey, ItemList list) {
            itemLists.put(publicKey, list);
            return Uni.createFrom().voidItem();
        }

        public Uni<Void> persist(String publicKey, ItemList list) {
            return put(publicKey, list);
        }

        public Uni<List<Integer>> takeIds(int count) {
            return Uni.createFrom().item(List.of());
        }

        public Uni<Void> addIds(List<Integer> ids) {
            return Uni.createFrom().voidItem();
        }

        public Uni<Void> removeIds(String publicKey, Collection<Integer> ids) {
            return Uni.createFrom().voidItem();
        }

        public Uni<Void> evict(String publicKey) {
            itemLists.remove(publicKey);
            return Uni.createFrom().voidItem();
        }
    };

    /**
     * The draws of the tests: each draw takes two numbers, the roll of the rank then the index of the character
     * among those of this rank. Once the script is empty the generator is a real one.
     */
    private final Deque<Integer> script = new ArrayDeque<>();
    private final Random random = new Random(42) {
        @Override
        public int nextInt(int bound) {
            return script.isEmpty() ? super.nextInt(bound) : script.poll();
        }
    };

    private final InMemoryCrews crews = new InMemoryCrews(PUB, USER_ID);
    private final CrewStore crewStore = crews.store();
    private final UserService users = new UserService(userRepo, userCache, lock, 10000);
    private final ItemService items = new ItemService(itemRepo, itemCache, users, lock, crewStore, 181, 4, 0);
    private final CharacterService characters = new CharacterService(crewStore, items, users, lock,
            publicKey -> Uni.createFrom().item(Map.of()));
    private final RecruitService recruits = new RecruitService(crewStore, characters, users, lock, COST, MULTI, random);
    private final RecruitEndpoints endpoints = new RecruitEndpoints(recruits);
    private final CharacterEndpoints characterEndpoints = new CharacterEndpoints(characters);

    private JsonNode call(RequestHandler handler, String json) throws Exception {
        return mapper.valueToTree(handler.handle(mapper.readTree(json)).await().indefinitely());
    }

    private JsonNode recruit(String privateKey, String fields) throws Exception {
        return call(endpoints.recruit(), "{\"user\":\"" + PUB + "\",\"private_key\":\"" + privateKey + "\"" + fields + "}");
    }

    private JsonNode recruit(int count) throws Exception {
        return recruit(PRIV, ",\"portal\":\"" + PORTAL + "\",\"count\":" + count);
    }

    /** The next draw gives the character at this index among those of the rank of the roll. */
    private void nextDraw(int rankRoll, int index) {
        script.add(rankRoll);
        script.add(index);
    }

    private int money() {
        return sessions.get(PUB).user().money();
    }

    // ---- catalog

    @Test
    void theCatalogHasTwentyCharactersOfEveryRank() {
        assertEquals(20, CharacterCatalog.all().size());
        Set<String> names = new HashSet<>();
        Map<Rank, Integer> byRank = new EnumMap<>(Rank.class);
        for (Info c : CharacterCatalog.all()) {
            assertTrue(names.add(c.name()), "duplicate name " + c.name());
            byRank.merge(c.rank(), 1, Integer::sum);
        }
        assertEquals(Rank.values().length, byRank.size(), "every rank can be drawn: " + byRank);
        assertNull(CharacterCatalog.get(0));
        assertNull(CharacterCatalog.get(21));
        assertEquals("Kentucky Johanes", CharacterCatalog.get(1).name());
    }

    @Test
    void aRarerCharacterHasMoreSkillsOfItsOwn() {
        assertEquals(0, CharacterCatalog.skillCount(Rank.D));
        assertEquals(0, CharacterCatalog.skillCount(Rank.C));
        assertEquals(1, CharacterCatalog.skillCount(Rank.B));
        assertEquals(5, CharacterCatalog.skillCount(Rank.SSS));
        for (Info c : CharacterCatalog.all()) {
            assertEquals(CharacterCatalog.skillCount(c.rank()), c.skills().size(), c.name());
            assertEquals(c.skills().size(), new HashSet<>(c.skills()).size(), c.name() + " has the same skill twice");
            assertTrue(c.skills().stream().noneMatch(s -> s.type() == Skill.Type.OTHER),
                    c.name() + " only has fight skills");
            assertEquals(c.skillIds(), c.toMap().get("skills"));
            assertTrue(c.agility() >= 1, c.name() + " has some agility");
        }
        assertEquals(List.of("war_cry"), CharacterCatalog.get(11).skillIds());
    }

    @Test
    void aStrongerRankIsRarerAndStronger() {
        Rank[] ranks = Rank.values();
        for (int i = 1; i < ranks.length; i++) {
            assertTrue(ranks[i].weight() < ranks[i - 1].weight(), ranks[i] + " must be rarer than " + ranks[i - 1]);
            int rank = i;
            int weakest = CharacterCatalog.all().stream().filter(c -> c.rank() == ranks[rank])
                    .mapToInt(c -> c.strength() + c.constitution() + c.agility() + c.luck()).min().orElseThrow();
            int strongestBelow = CharacterCatalog.all().stream().filter(c -> c.rank() == ranks[rank - 1])
                    .mapToInt(c -> c.strength() + c.constitution() + c.agility() + c.luck()).max().orElseThrow();
            assertTrue(weakest > strongestBelow, ranks[i] + " must be stronger than " + ranks[i - 1]);
        }
    }

    @Test
    void theRollsFollowTheRates() {
        assertEquals(1000, Rank.TOTAL_WEIGHT);
        Map<Rank, Integer> rolls = new EnumMap<>(Rank.class);
        for (int roll = 0; roll < Rank.TOTAL_WEIGHT; roll++) {
            rolls.merge(Rank.fromRoll(roll), 1, Integer::sum);
        }
        for (Rank rank : Rank.values()) {
            assertEquals(rank.weight(), rolls.get(rank), "rolls of " + rank);
        }
        assertEquals(1, rolls.get(Rank.SSS), "one roll out of 1000: 0.1 %");
        assertEquals(0.1, Rank.SSS.percent(), 1e-9);
        assertEquals(40.0, Rank.D.percent(), 1e-9);
    }

    // ---- getPortals

    @Test
    void thePortalsAreDescribedWithTheirRatesAndCharacters() throws Exception {
        JsonNode r = call(endpoints.getPortals(), "{}");
        assertEquals(200, r.get("status").asInt());
        JsonNode portals = r.at("/payload/portals");
        assertEquals(1, portals.size());
        JsonNode portal = portals.get(0);
        assertEquals(PORTAL, portal.get("id").asText());
        assertEquals("Masterpack vol. 1", portal.get("name").asText());
        assertEquals(COST, portal.get("cost").asInt());
        assertEquals(MULTI, portal.get("multi").asInt());
        assertEquals(20, portal.get("characters").size());
        assertEquals("SSS", portal.at("/characters/19/rank").asText());
        assertEquals(22, portal.at("/characters/19/stats/for").asInt());

        JsonNode rates = portal.get("rates");
        assertEquals(7, rates.size());
        assertEquals("SSS", rates.at("/0/rank").asText());
        assertEquals(0.1, rates.at("/0/percent").asDouble(), 1e-9);
        assertEquals("D", rates.at("/6/rank").asText());
        double total = 0;
        for (JsonNode rate : rates) {
            total += rate.get("percent").asDouble();
        }
        assertEquals(100.0, total, 1e-9);
    }

    // ---- recruit

    @Test
    void aNewCharacterJoinsTheCrew() throws Exception {
        nextDraw(ROLL_SSS, 0);
        JsonNode r = recruit(1);
        assertEquals(200, r.get("status").asInt());
        JsonNode draws = r.at("/payload/draws");
        assertEquals(1, draws.size());
        assertEquals(20, draws.at("/0/num").asInt());
        assertEquals("L'Oracle aux Mille Clés", draws.at("/0/name").asText());
        assertEquals("SSS", draws.at("/0/rank").asText());
        assertTrue(draws.at("/0/isNew").asBoolean());
        assertEquals(1, draws.at("/0/copies").asInt());

        assertEquals(START_MONEY - COST, r.at("/payload/money").asInt());
        assertEquals(START_MONEY - COST, money());

        JsonNode list = r.at("/payload/characters");
        assertEquals(4, list.size());
        assertEquals(20, list.at("/3/num").asInt());
        assertEquals("SSS", list.at("/3/rank").asText());
        assertTrue(list.at("/3/equipment").isEmpty());
        assertTrue(list.at("/3/expeditionId").isNull());

        assertTrue(crews.cache.get(PUB).owns(20));
        assertTrue(crews.cache.get(PUB).dirty());
        assertTrue(sessions.get(PUB).dirty(), "the next synchronization must write the character");
        assertFalse(crews.database.get(PUB).containsKey(20), "only Redis is written");
    }

    @Test
    void aCharacterTheUserAlreadyHasIsADuplicate() throws Exception {
        // the characters of rank D are 2, 3, 4, 5 and 6: the first one is a character every user starts with
        nextDraw(ROLL_D, 0);
        JsonNode r = recruit(1);
        assertEquals(200, r.get("status").asInt());
        assertEquals(2, r.at("/payload/draws/0/num").asInt());
        assertFalse(r.at("/payload/draws/0/isNew").asBoolean());
        assertEquals(2, r.at("/payload/draws/0/copies").asInt());
        assertEquals(3, r.at("/payload/characters").size(), "no character was added");
        assertEquals(2, r.at("/payload/characters/1/copies").asInt());
        assertEquals(1, r.at("/payload/characters/0/copies").asInt());
        assertEquals(START_MONEY - COST, money(), "a duplicate is paid too");
    }

    @Test
    void tenDrawsAreMadeAtOnce() throws Exception {
        nextDraw(ROLL_S, 0);
        nextDraw(ROLL_S, 0);
        nextDraw(ROLL_D, 2);
        JsonNode r = recruit(MULTI);
        assertEquals(200, r.get("status").asInt());
        JsonNode draws = r.at("/payload/draws");
        assertEquals(MULTI, draws.size());
        assertEquals(18, draws.at("/0/num").asInt());
        assertTrue(draws.at("/0/isNew").asBoolean());
        assertEquals(18, draws.at("/1/num").asInt(), "the same character twice in the same request");
        assertFalse(draws.at("/1/isNew").asBoolean());
        assertEquals(2, draws.at("/1/copies").asInt());
        assertEquals(4, draws.at("/2/num").asInt());
        assertEquals(START_MONEY - MULTI * COST, money());

        Set<Integer> owned = new HashSet<>();
        r.at("/payload/characters").forEach(c -> owned.add(c.get("num").asInt()));
        Set<Integer> expected = new HashSet<>(Set.of(1, 2, 3));
        draws.forEach(d -> expected.add(d.get("num").asInt()));
        assertEquals(expected, owned);
        assertEquals(owned, crews.cache.get(PUB).characters().keySet());
    }

    @Test
    void nothingIsDrawnWithoutEnoughMoney() throws Exception {
        assertEquals(200, recruit(MULTI).get("status").asInt());
        int left = money();
        assertEquals(START_MONEY - MULTI * COST, left);
        Map<Integer, Map<Integer, Integer>> before = crews.cache.get(PUB).characters();

        JsonNode r = recruit(MULTI);
        assertEquals(402, r.get("status").asInt());
        assertEquals(left, r.at("/payload/money").asInt());
        assertEquals(left, money());
        assertEquals(before, crews.cache.get(PUB).characters());
        assertEquals(200, recruit(1).get("status").asInt(), "one draw is still affordable");
    }

    @Test
    void recruitChecksItsParameters() throws Exception {
        assertEquals(401, recruit("bad", ",\"portal\":\"" + PORTAL + "\",\"count\":1").get("status").asInt());
        assertEquals(400, recruit(PRIV, ",\"count\":1").get("status").asInt(), "a portal is needed");
        assertEquals(404, recruit(PRIV, ",\"portal\":\"nope\",\"count\":1").get("status").asInt());
        assertEquals(400, recruit(0).get("status").asInt());
        assertEquals(400, recruit(5).get("status").asInt(), "one draw or ten, nothing else");
        assertEquals(400, recruit(-1).get("status").asInt());
        assertEquals(400, recruit(PRIV, ",\"portal\":\"" + PORTAL + "\",\"count\":\"1\"").get("status").asInt());
        assertEquals(400, call(endpoints.recruit(), "{\"user\":\"" + PUB + "\",\"portal\":\"" + PORTAL + "\"}")
                .get("status").asInt());
        assertTrue(sessions.isEmpty() || money() == START_MONEY, "nothing was paid");

        assertEquals(200, recruit(PRIV, ",\"portal\":\"" + PORTAL + "\"").get("status").asInt(), "one draw by default");
        assertEquals(START_MONEY - COST, money());
    }

    // ---- with the rest of the characters

    @Test
    void onlyACharacterOfTheCrewCanBeEquipped() throws Exception {
        String equip = "{\"user\":\"" + PUB + "\",\"private_key\":\"" + PRIV + "\",\"character\":20,\"slot\":3,\"item\":10}";
        assertEquals(404, call(characterEndpoints.equipItem(), equip).get("status").asInt(), "not recruited yet");

        nextDraw(ROLL_SSS, 0);
        recruit(1);
        JsonNode r = call(characterEndpoints.equipItem(), equip);
        assertEquals(200, r.get("status").asInt());
        assertEquals(10, r.at("/payload/characters/3/equipment/3/id").asInt());
    }

    @Test
    void theSynchronizationWritesTheRecruitsToTheDatabase() throws Exception {
        nextDraw(ROLL_SSS, 0);
        nextDraw(ROLL_SSS, 0);
        recruit(1);
        recruit(1);
        CacheSyncService sync = new CacheSyncService(userCache, itemCache, userRepo, itemRepo, lock, crewStore,
                Duration.ofMinutes(3), Duration.ofHours(1));
        // idle for long enough: written, then dropped from Redis
        sync.syncDue(Instant.now().plus(Duration.ofHours(2))).await().indefinitely();

        assertEquals(Set.of(1, 2, 3, 20), crews.database.get(PUB).keySet());
        assertEquals(2, crews.copies.get(PUB).get(20));
        assertTrue(crews.cache.isEmpty());

        // read again from the database at the next request
        JsonNode list = call(characterEndpoints.getCharacters(),
                "{\"user\":\"" + PUB + "\",\"private_key\":\"" + PRIV + "\"}").at("/payload/characters");
        assertEquals(4, list.size());
        assertEquals(20, list.at("/3/num").asInt());
        assertEquals(2, list.at("/3/copies").asInt());
    }
}
