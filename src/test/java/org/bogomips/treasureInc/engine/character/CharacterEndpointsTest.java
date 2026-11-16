package org.bogomips.treasureInc.engine.character;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.bogomips.treasureInc.engine.character.CrewStore.Crew;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class CharacterEndpointsTest {

    private static final String PUB = "12345678900";
    private static final String PRIV = "secret";
    private static final int USER_ID = 1;

    private static final int SWORD = 10;
    private static final int BETTER_SWORD = 11;
    private static final int SHIELD = 12;
    private static final int TRINKET = 13;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedSession> sessions = new HashMap<>();
    private final Map<String, ItemList> itemLists = new HashMap<>();
    /** Expedition id by character number: the busy characters. */
    private final Map<Integer, String> busy = new HashMap<>();
    private final List<Integer> soldInDatabase = new ArrayList<>();
    private final List<ItemInfo> dbItems = List.of(
            new ItemInfo(SWORD, "épée terne", "d", 91, 100, "common", "t", "t", null, Map.of("slot", 3, "for", 4)),
            new ItemInfo(BETTER_SWORD, "lame rare", "d", 92, 900, "rare", "t", "t", null,
                    Map.of("slot", 3, "for", 12, "spe1", "luck_charm")),
            new ItemInfo(SHIELD, "bouclier terne", "d", 148, 120, "common", "t", "t", null, Map.of("slot", 4, "con", 2)),
            new ItemInfo(TRINKET, "babiole terne", "d", 60, 50, "common", "t", "t"));

    private final UserService.Repository userRepo = new UserService.Repository() {
        public Uni<UserInfo> login(String pub, String priv) {
            return authenticate(pub, priv);
        }

        public Uni<UserInfo> authenticate(String pub, String priv) {
            return Uni.createFrom().item(PUB.equals(pub) && PRIV.equals(priv)
                    ? new UserInfo(USER_ID, pub, 1000, "t", "t", "t") : null);
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
            soldInDatabase.addAll(ids);
            return Uni.createFrom().item(new SellResult(SellResult.Status.OK, 50, 1050, ids));
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
            ItemList list = itemLists.get(publicKey);
            itemLists.put(publicKey, new ItemList(true,
                    list.items().stream().filter(i -> !ids.contains(i.id())).toList(), list.pendingIds()));
            return Uni.createFrom().voidItem();
        }

        public Uni<Void> evict(String publicKey) {
            itemLists.remove(publicKey);
            return Uni.createFrom().voidItem();
        }
    };

    private final InMemoryCrews crews = new InMemoryCrews(PUB, USER_ID);
    private final CrewStore crewStore = crews.store();
    private final UserService users = new UserService(userRepo, userCache, lock, 10000);
    private final ItemService items = new ItemService(itemRepo, itemCache, users, lock, crewStore, 181, 4, 0);
    private final CharacterService characters = new CharacterService(crewStore, items, users, lock,
            publicKey -> Uni.createFrom().item(Map.copyOf(busy)));
    private final CharacterEndpoints endpoints = new CharacterEndpoints(characters);

    private JsonNode call(RequestHandler handler, String privateKey, String fields) throws Exception {
        JsonNode params = mapper.readTree("{\"user\":\"" + PUB + "\",\"private_key\":\"" + privateKey + "\""
                + (fields.isEmpty() ? "" : "," + fields) + "}");
        return mapper.valueToTree(handler.handle(params).await().indefinitely());
    }

    private JsonNode list() throws Exception {
        return call(endpoints.getCharacters(), PRIV, "");
    }

    private JsonNode equip(int character, int slot, int item) throws Exception {
        return call(endpoints.equipItem(), PRIV, "\"character\":" + character + ",\"slot\":" + slot + ",\"item\":" + item);
    }

    private JsonNode unequip(int character, int slot) throws Exception {
        return call(endpoints.unequipItem(), PRIV, "\"character\":" + character + ",\"slot\":" + slot);
    }

    private List<Integer> inventory() {
        return items.page(PUB, 1, 100).await().indefinitely().items().stream().map(ItemInfo::id).toList();
    }

    // ---- getCharacters

    @Test
    void aUserHasThreeCharactersWearingNothing() throws Exception {
        JsonNode r = list();
        assertEquals(200, r.get("status").asInt());
        JsonNode list = r.at("/payload/characters");
        assertEquals(3, list.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(i + 1, list.get(i).get("num").asInt());
            assertEquals(1, list.get(i).get("copies").asInt());
            assertTrue(list.get(i).get("expeditionId").isNull());
            assertTrue(list.get(i).get("equipment").isEmpty());
        }
        assertEquals("Kentucky Johanes", list.at("/0/name").asText());
        assertEquals("C", list.at("/0/rank").asText());
        assertEquals(4, list.at("/0/stats/for").asInt());
        assertEquals(Set.of(1, 2, 3), crews.database.get(PUB).keySet(), "the characters are created in the database");
        assertFalse(sessions.get(PUB).dirty(), "nothing to synchronize");
    }

    @Test
    void theCharactersAreReadFromTheDatabaseOnce() throws Exception {
        list();
        list();
        assertEquals(1, crews.loads);
    }

    @Test
    void listNeedsThePrivateKey() throws Exception {
        assertEquals(401, call(endpoints.getCharacters(), "bad", "").get("status").asInt());
        JsonNode noKey = mapper.readTree("{\"user\":\"" + PUB + "\"}");
        JsonNode r = mapper.valueToTree(endpoints.getCharacters().handle(noKey).await().indefinitely());
        assertEquals(400, r.get("status").asInt());
    }

    @Test
    void aBusyCharacterShowsItsExpedition() throws Exception {
        busy.put(2, "exp-1");
        JsonNode list = list().at("/payload/characters");
        assertTrue(list.get(0).get("expeditionId").isNull());
        assertEquals("exp-1", list.get(1).get("expeditionId").asText());
    }

    // ---- equipItem

    @Test
    void equipMovesTheItemFromTheInventoryToTheSlot() throws Exception {
        assertEquals(List.of(SWORD, BETTER_SWORD, SHIELD, TRINKET), inventory());

        JsonNode r = equip(1, 3, SWORD);
        assertEquals(200, r.get("status").asInt());
        JsonNode worn = r.at("/payload/characters/0/equipment");
        assertEquals(1, worn.size());
        assertEquals(SWORD, worn.at("/3/id").asInt());
        assertEquals("épée terne", worn.at("/3/name").asText());
        assertEquals(4, worn.at("/3/stats/for").asInt());
        assertTrue(r.at("/payload/characters/1/equipment").isEmpty());

        assertEquals(List.of(BETTER_SWORD, SHIELD, TRINKET), inventory());
        assertEquals(Map.of(3, SWORD), crews.cache.get(PUB).equipment(1));
        assertTrue(crews.cache.get(PUB).dirty());
        assertTrue(sessions.get(PUB).dirty(), "the next synchronization must write the equipment");
        assertEquals(Map.of(), crews.database.get(PUB).get(1), "only Redis is written");
    }

    @Test
    void equippingAnOccupiedSlotSendsTheOldItemBackToTheInventory() throws Exception {
        equip(1, 3, SWORD);
        JsonNode r = equip(1, 3, BETTER_SWORD);
        assertEquals(200, r.get("status").asInt());
        assertEquals(BETTER_SWORD, r.at("/payload/characters/0/equipment/3/id").asInt());
        assertEquals(List.of(SWORD, SHIELD, TRINKET), inventory());
    }

    @Test
    void severalCharactersAndSlotsCanBeEquipped() throws Exception {
        equip(1, 3, SWORD);
        equip(1, 4, SHIELD);
        equip(3, 3, BETTER_SWORD);
        JsonNode list = list().at("/payload/characters");
        assertEquals(SWORD, list.at("/0/equipment/3/id").asInt());
        assertEquals(SHIELD, list.at("/0/equipment/4/id").asInt());
        assertTrue(list.at("/1/equipment").isEmpty());
        assertEquals(BETTER_SWORD, list.at("/2/equipment/3/id").asInt());
        assertEquals(List.of(TRINKET), inventory());
    }

    @Test
    void anItemOnlyFitsItsSlot() throws Exception {
        assertEquals(400, equip(1, 4, SWORD).get("status").asInt(), "a sword in the shield slot");
        assertEquals(400, equip(1, 3, TRINKET).get("status").asInt(), "an item without stats");
        assertEquals(Map.of(), crews.cache.get(PUB).equipment(1));
        assertFalse(sessions.get(PUB).dirty());
    }

    @Test
    void anItemCannotBeWornTwice() throws Exception {
        equip(1, 3, SWORD);
        assertEquals(404, equip(2, 3, SWORD).get("status").asInt(), "it is no longer in the inventory");
        assertEquals(404, equip(1, 3, 999).get("status").asInt(), "unknown item");
        assertEquals(Map.of(), crews.cache.get(PUB).equipment(2));
    }

    @Test
    void aBusyCharacterKeepsItsEquipment() throws Exception {
        equip(2, 3, SWORD);
        busy.put(2, "exp-1");
        assertEquals(409, equip(2, 4, SHIELD).get("status").asInt());
        assertEquals(409, unequip(2, 3).get("status").asInt());
        assertEquals(Map.of(3, SWORD), crews.cache.get(PUB).equipment(2));
        assertEquals(200, equip(1, 4, SHIELD).get("status").asInt(), "the other characters are free");
    }

    @Test
    void equipChecksItsParameters() throws Exception {
        RequestHandler h = endpoints.equipItem();
        assertEquals(401, call(h, "bad", "\"character\":1,\"slot\":3,\"item\":" + SWORD).get("status").asInt());
        assertEquals(400, call(h, PRIV, "\"slot\":3,\"item\":" + SWORD).get("status").asInt());
        assertEquals(400, call(h, PRIV, "\"character\":1,\"item\":" + SWORD).get("status").asInt());
        assertEquals(400, call(h, PRIV, "\"character\":1,\"slot\":3").get("status").asInt());
        assertEquals(400, equip(0, 3, SWORD).get("status").asInt());
        assertEquals(400, equip(21, 3, SWORD).get("status").asInt(), "no such character");
        assertEquals(404, equip(4, 3, SWORD).get("status").asInt(), "a character the user does not have");
        assertEquals(400, equip(1, 0, SWORD).get("status").asInt());
        assertEquals(400, equip(1, 10, SWORD).get("status").asInt());
        assertEquals(400, call(h, PRIV, "\"character\":\"1\",\"slot\":3,\"item\":" + SWORD).get("status").asInt());
        assertTrue(crews.cache.isEmpty() || !crews.cache.get(PUB).dirty());
    }

    // ---- unequipItem

    @Test
    void unequipSendsTheItemBackToTheInventory() throws Exception {
        equip(1, 3, SWORD);
        JsonNode r = unequip(1, 3);
        assertEquals(200, r.get("status").asInt());
        assertTrue(r.at("/payload/characters/0/equipment").isEmpty());
        assertEquals(List.of(SWORD, BETTER_SWORD, SHIELD, TRINKET), inventory());
    }

    @Test
    void unequippingAnEmptySlotChangesNothing() throws Exception {
        JsonNode r = unequip(1, 3);
        assertEquals(200, r.get("status").asInt());
        assertTrue(r.at("/payload/characters/0/equipment").isEmpty());
    }

    @Test
    void unequipChecksItsParameters() throws Exception {
        RequestHandler h = endpoints.unequipItem();
        assertEquals(401, call(h, "bad", "\"character\":1,\"slot\":3").get("status").asInt());
        assertEquals(400, call(h, PRIV, "\"slot\":3").get("status").asInt());
        assertEquals(400, call(h, PRIV, "\"character\":1").get("status").asInt());
        assertEquals(400, unequip(21, 3).get("status").asInt());
        assertEquals(404, unequip(4, 3).get("status").asInt(), "a character the user does not have");
        assertEquals(400, unequip(1, 10).get("status").asInt());
    }

    // ---- with the items

    @Test
    void anEquippedItemCannotBeSold() throws Exception {
        equip(1, 3, SWORD);
        SellResult refused = items.sell(PUB, PRIV, List.of(SWORD, TRINKET)).await().indefinitely();
        assertEquals(SellResult.Status.NOT_FOUND, refused.status());
        assertTrue(soldInDatabase.isEmpty());

        unequip(1, 3);
        assertEquals(SellResult.Status.OK, items.sell(PUB, PRIV, List.of(SWORD)).await().indefinitely().status());
        assertEquals(List.of(SWORD), soldInDatabase);
    }

    @Test
    void theItemsWornByACharacterAreKnown() throws Exception {
        equip(2, 3, BETTER_SWORD);
        equip(2, 4, SHIELD);
        List<ItemInfo> worn = characters.equippedLocked(PUB, 2).await().indefinitely();
        assertEquals(List.of(BETTER_SWORD, SHIELD), worn.stream().map(ItemInfo::id).toList());
        assertTrue(characters.equippedLocked(PUB, 1).await().indefinitely().isEmpty());
    }

    // ---- synchronization

    @Test
    void theSynchronizationWritesTheEquipmentToTheDatabase() throws Exception {
        equip(1, 3, SWORD);
        equip(3, 4, SHIELD);
        // the session is due: its last synchronization is old
        CachedSession session = sessions.get(PUB);
        sessions.put(PUB, new CachedSession(session.user(), session.lastAction(), session.keyHash(),
                Instant.now().minus(Duration.ofHours(1)).toString(), true));
        CacheSyncService sync = new CacheSyncService(userCache, itemCache, userRepo, itemRepo, lock, crewStore,
                Duration.ofMinutes(3), Duration.ofHours(1));

        assertEquals(1, sync.syncDue(Instant.now()).await().indefinitely());
        assertEquals(Map.of(3, SWORD), crews.database.get(PUB).get(1));
        assertEquals(Map.of(), crews.database.get(PUB).get(2));
        assertEquals(Map.of(4, SHIELD), crews.database.get(PUB).get(3));
        assertFalse(crews.cache.get(PUB).dirty());
        assertFalse(sessions.get(PUB).dirty());

        // nothing changed since: the characters are not written again
        sessions.put(PUB, sessions.get(PUB).markDirty());
        Crew before = crews.cache.get(PUB);
        sync.syncDue(Instant.now().plus(Duration.ofHours(2))).await().indefinitely();
        assertEquals(1, crews.saves);
        assertEquals(before.characters(), crews.database.get(PUB));
    }

    @Test
    void anIdleSessionDropsItsCharactersFromRedis() throws Exception {
        equip(1, 3, SWORD);
        CacheSyncService sync = new CacheSyncService(userCache, itemCache, userRepo, itemRepo, lock, crewStore,
                Duration.ofMinutes(3), Duration.ofHours(1));
        sync.syncDue(Instant.now().plus(Duration.ofHours(2))).await().indefinitely();

        assertTrue(sessions.isEmpty());
        assertTrue(crews.cache.isEmpty());
        assertEquals(Map.of(3, SWORD), crews.database.get(PUB).get(1), "written before being dropped");
        // read again from the database at the next request
        assertEquals(SWORD, list().at("/payload/characters/0/equipment/3/id").asInt());
    }
}
