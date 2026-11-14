package org.bogomips.treasureInc.engine.expedition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.bogomips.treasureInc.engine.expedition.ExpeditionService.Expedition;
import org.bogomips.treasureInc.engine.item.ItemInfo;
import org.bogomips.treasureInc.engine.item.ItemService;
import org.bogomips.treasureInc.engine.item.ItemService.ItemList;
import org.bogomips.treasureInc.engine.item.ItemService.SellResult;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;
import org.bogomips.treasureInc.engine.user.UserInfo;
import org.bogomips.treasureInc.engine.user.UserService;
import org.bogomips.treasureInc.engine.user.UserService.CachedSession;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ExpeditionEndpointsTest {

    private static final String PUB = "12345678900";
    private static final String PRIV = "secret";
    private static final int START_MONEY = 100000;
    private static final int GRAPHICS_COUNT = 7;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedSession> sessions = new HashMap<>();
    private final Map<String, ItemList> itemLists = new HashMap<>();
    /** Dug cases by "user/location/case", expeditions by "user/id". */
    private final Map<String, Integer> digs = new HashMap<>();
    private final Map<String, Expedition> stored = new HashMap<>();
    private final List<String> locked = new ArrayList<>();
    private int sequence = 100;

    /** Only the credentials check is used: the session is opened from the database on the first call. */
    private final UserService.Repository userRepo = new UserService.Repository() {
        public Uni<UserInfo> login(String pub, String priv) {
            return authenticate(pub, priv);
        }

        public Uni<UserInfo> authenticate(String pub, String priv) {
            return Uni.createFrom().item(PUB.equals(pub) && PRIV.equals(priv)
                    ? new UserInfo(1, pub, START_MONEY, "t", "t", "t") : null);
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

    /** Single-threaded tests need no mutual exclusion; only records the keys it was asked to lock. */
    private final UserService.Lock lock = new UserService.Lock() {
        public <T> Uni<T> withLock(String publicKey, Supplier<Uni<T>> action) {
            locked.add(publicKey);
            return Uni.createFrom().deferred(action::get);
        }
    };

    private final ItemService.Repository itemRepo = new ItemService.Repository() {
        public Uni<ItemList> findByPublicKey(String publicKey) {
            return Uni.createFrom().item(new ItemList(true, List.of()));
        }

        public Uni<SellResult> sell(String publicKey, String privateKey, List<Integer> ids, int extraMoney) {
            throw new UnsupportedOperationException();
        }

        public Uni<List<Integer>> nextIds(int count) {
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ids.add(++sequence);
            }
            return Uni.createFrom().item(ids);
        }

        public Uni<Void> insertAll(int userId, List<ItemInfo> items) {
            throw new UnsupportedOperationException();
        }

        public Uni<Void> deleteByIds(int userId, List<Integer> ids) {
            throw new UnsupportedOperationException();
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
            throw new UnsupportedOperationException();
        }

        public Uni<Void> evict(String publicKey) {
            itemLists.remove(publicKey);
            return Uni.createFrom().voidItem();
        }
    };

    private final ExpeditionService.Store store = new ExpeditionService.Store() {
        public Uni<Integer> dugRarity(String publicKey, String location, int caseId) {
            return Uni.createFrom().item(digs.get(digKey(location, caseId)));
        }

        public Uni<Void> putDug(String publicKey, String location, int caseId, int rarity) {
            digs.put(digKey(location, caseId), rarity);
            return Uni.createFrom().voidItem();
        }

        public Uni<Void> clearDug(String publicKey, String location) {
            digs.keySet().removeIf(key -> key.startsWith(location + "/"));
            return Uni.createFrom().voidItem();
        }

        public Uni<Expedition> get(String publicKey, String expeditionId) {
            return Uni.createFrom().item(stored.get(expeditionId));
        }

        public Uni<Void> put(String publicKey, Expedition expedition) {
            stored.put(expedition.id(), expedition);
            return Uni.createFrom().voidItem();
        }

        public Uni<Void> remove(String publicKey, String expeditionId) {
            stored.remove(expeditionId);
            return Uni.createFrom().voidItem();
        }
    };

    private final UserService users = new UserService(userRepo, userCache, lock, 10000);
    private final ItemService items = new ItemService(itemRepo, itemCache, users, lock, GRAPHICS_COUNT, 4);
    private final ExpeditionRules rules = new ExpeditionRules(ExpeditionRulesTest.config());
    private final ExpeditionEndpoints endpoints =
            new ExpeditionEndpoints(new ExpeditionService(store, users, items, lock, rules), rules);

    private static String digKey(String location, int caseId) {
        return location + "/" + caseId;
    }

    private JsonNode call(RequestHandler handler, String fields) throws Exception {
        JsonNode params = mapper.readTree("{\"user\":\"" + PUB + "\"," + fields + "}");
        return mapper.valueToTree(handler.handle(params).await().indefinitely());
    }

    private JsonNode dig(String fields) throws Exception {
        return call(endpoints.digCase(), "\"private_key\":\"" + PRIV + "\"," + fields);
    }

    private JsonNode start(String fields) throws Exception {
        return call(endpoints.startExpedition(), "\"private_key\":\"" + PRIV + "\"," + fields);
    }

    private JsonNode open(String expeditionId) throws Exception {
        return call(endpoints.generateItemsForUser(),
                "\"private_key\":\"" + PRIV + "\",\"expedition_id\":\"" + expeditionId + "\"");
    }

    private int money() {
        return sessions.get(PUB).user().money();
    }

    // ---- digCase

    @Test
    void digPaysAndKeepsTheRarity() throws Exception {
        JsonNode r = dig("\"location\":\"Mordak\",\"case_id\":3,\"cost\":500");
        assertEquals(200, r.get("status").asInt());
        int rarity = r.at("/payload/rarity").asInt(-1);
        assertTrue(rarity >= 0 && rarity <= 4, "rarity " + rarity);
        assertEquals(START_MONEY - 500, r.at("/payload/money").asInt());
        assertEquals(START_MONEY - 500, money());
        assertEquals(rarity, digs.get("mordak/3"));
        assertEquals(List.of(PUB), locked);
    }

    @RepeatedTest(20)
    void diggingACaseAgainIsFreeAndKeepsTheRarity() throws Exception {
        int first = dig("\"location\":\"mordak\",\"case_id\":3,\"cost\":500").at("/payload/rarity").asInt();

        JsonNode again = dig("\"location\":\"mordak\",\"case_id\":3,\"cost\":500");
        assertEquals(200, again.get("status").asInt());
        assertEquals(first, again.at("/payload/rarity").asInt());
        assertEquals(START_MONEY - 500, again.at("/payload/money").asInt());
        assertEquals(START_MONEY - 500, money());
    }

    @Test
    void theSameCaseOfAnotherLocationIsAnotherCase() throws Exception {
        dig("\"location\":\"mordak\",\"case_id\":3,\"cost\":500");
        dig("\"location\":\"gardakan\",\"case_id\":3,\"cost\":500");
        assertEquals(START_MONEY - 1000, money());
    }

    @Test
    void digRefusesWhenTooPoor() throws Exception {
        JsonNode r = dig("\"location\":\"mordak\",\"case_id\":3,\"cost\":" + (START_MONEY + 1));
        assertEquals(402, r.get("status").asInt());
        assertEquals(START_MONEY, r.at("/payload/money").asInt());
        assertEquals(START_MONEY, money());
        assertTrue(digs.isEmpty(), "the case stays to dig");
    }

    @Test
    void digCanBeFree() throws Exception {
        assertEquals(200, dig("\"location\":\"mordak\",\"case_id\":0,\"cost\":0").get("status").asInt());
        assertEquals(START_MONEY, money());
    }

    @Test
    void digChecksItsParameters() throws Exception {
        RequestHandler h = endpoints.digCase();
        assertEquals(401, call(h, "\"private_key\":\"bad\",\"location\":\"mordak\",\"case_id\":1,\"cost\":1").get("status").asInt());
        assertEquals(400, dig("\"location\":\"morgoth\",\"case_id\":1,\"cost\":1").get("status").asInt());
        assertEquals(400, dig("\"case_id\":1,\"cost\":1").get("status").asInt());
        assertEquals(400, dig("\"location\":\"mordak\",\"cost\":1").get("status").asInt());
        assertEquals(400, dig("\"location\":\"mordak\",\"case_id\":null,\"cost\":1").get("status").asInt());
        assertEquals(400, dig("\"location\":\"mordak\",\"case_id\":-1,\"cost\":1").get("status").asInt());
        assertEquals(400, dig("\"location\":\"mordak\",\"case_id\":18,\"cost\":1").get("status").asInt());
        assertEquals(400, dig("\"location\":\"mordak\",\"case_id\":1").get("status").asInt());
        assertEquals(400, dig("\"location\":\"mordak\",\"case_id\":1,\"cost\":-1").get("status").asInt());
        assertEquals(400, dig("\"location\":\"mordak\",\"case_id\":1,\"cost\":\"1\"").get("status").asInt());
        assertTrue(digs.isEmpty());
    }

    // ---- startExpedition

    @Test
    void startPaysAndStoresTheExpedition() throws Exception {
        digs.put("gardakan/5", 2);

        JsonNode r = start("\"location\":\"Gardakan\",\"difficulty\":5,\"case_id\":5");
        assertEquals(200, r.get("status").asInt());
        assertEquals(START_MONEY - 1000, r.at("/payload/money").asInt());
        assertEquals(START_MONEY - 1000, money());
        assertTrue(sessions.get(PUB).dirty(), "the debit must be written by the next synchronization");
        assertEquals(8, r.at("/payload/itemCount").asInt());

        Expedition expedition = stored.get(r.at("/payload/expeditionId").asText());
        assertEquals("gardakan", expedition.location());
        assertEquals(5, expedition.difficulty());
        assertEquals(2, expedition.rarity());
        assertEquals(8, expedition.itemCount());
    }

    @Test
    void startForgetsTheDugCasesOfItsLocationOnly() throws Exception {
        digs.put("gardakan/5", 2);
        digs.put("gardakan/6", 1);
        digs.put("mordak/5", 4);

        start("\"location\":\"gardakan\",\"difficulty\":1,\"case_id\":5");

        assertEquals(Map.of("mordak/5", 4), digs);
    }

    @Test
    void startWithoutACaseHasNoRarity() throws Exception {
        JsonNode r = start("\"location\":\"mordak\",\"difficulty\":10");
        assertEquals(200, r.get("status").asInt());
        assertEquals(8, r.at("/payload/itemCount").asInt());
    }

    @Test
    void startWithACaseThatWasNotDugHasNoRarity() throws Exception {
        JsonNode r = start("\"location\":\"mordak\",\"difficulty\":10,\"case_id\":4");
        assertEquals(200, r.get("status").asInt());
        assertEquals(8, r.at("/payload/itemCount").asInt());
    }

    @Test
    void startRefusesWhenTooPoor() throws Exception {
        sessions.put(PUB, new CachedSession(new UserInfo(1, PUB, 999, "t", "t", "t"), "old", CachedSession.hashKey(PRIV)));
        digs.put("mordak/5", 2);

        JsonNode r = start("\"location\":\"mordak\",\"difficulty\":10,\"case_id\":5");
        assertEquals(402, r.get("status").asInt());
        assertEquals(999, r.at("/payload/money").asInt());
        assertEquals(999, money());
        assertFalse(sessions.get(PUB).dirty());
        assertTrue(stored.isEmpty());
        assertEquals(2, digs.get("mordak/5"), "the case is still dug");
    }

    @Test
    void startChecksItsParameters() throws Exception {
        RequestHandler h = endpoints.startExpedition();
        assertEquals(401, call(h, "\"private_key\":\"bad\",\"location\":\"mordak\",\"difficulty\":1").get("status").asInt());
        assertEquals(400, start("\"location\":\"morgoth\",\"difficulty\":1").get("status").asInt());
        assertEquals(400, start("\"difficulty\":1").get("status").asInt());
        assertEquals(400, start("\"location\":\"mordak\"").get("status").asInt());
        assertEquals(400, start("\"location\":\"mordak\",\"difficulty\":0").get("status").asInt());
        assertEquals(400, start("\"location\":\"mordak\",\"difficulty\":11").get("status").asInt());
        assertEquals(400, start("\"location\":\"mordak\",\"difficulty\":\"1\"").get("status").asInt());
        assertEquals(400, start("\"location\":\"mordak\",\"difficulty\":1,\"case_id\":18").get("status").asInt());
        assertEquals(400, start("\"location\":\"mordak\",\"difficulty\":1,\"case_id\":\"1\"").get("status").asInt());
        assertTrue(stored.isEmpty());
    }

    // ---- generateItemsForUser

    @Test
    void openingAChestGivesTheItemsOfTheExpeditionOnce() throws Exception {
        digs.put("bobafett/0", 1);
        JsonNode sent = start("\"location\":\"bobafett\",\"difficulty\":5,\"case_id\":0");
        String id = sent.at("/payload/expeditionId").asText();
        int expected = sent.at("/payload/itemCount").asInt();
        locked.clear();

        JsonNode r = open(id);
        assertEquals(200, r.get("status").asInt());
        assertEquals(expected, r.at("/payload/items").size());
        assertEquals(expected, itemLists.get(PUB).items().size());
        assertTrue(stored.isEmpty(), "the expedition is over");
        assertEquals(List.of(PUB), locked);

        assertEquals(404, open(id).get("status").asInt());
        assertEquals(expected, itemLists.get(PUB).items().size(), "no item from the second opening");
    }

    @Test
    void openingChecksItsParameters() throws Exception {
        String id = start("\"location\":\"mordak\",\"difficulty\":1").at("/payload/expeditionId").asText();

        RequestHandler h = endpoints.generateItemsForUser();
        assertEquals(401, call(h, "\"private_key\":\"bad\",\"expedition_id\":\"" + id + "\"").get("status").asInt());
        assertEquals(400, call(h, "\"private_key\":\"" + PRIV + "\"").get("status").asInt());
        assertEquals(400, call(h, "\"private_key\":\"" + PRIV + "\",\"nb\":100").get("status").asInt());
        assertEquals(404, open("unknown").get("status").asInt());
        assertEquals(1, stored.size(), "the expedition is still under way");
    }

    @RepeatedTest(20)
    void nameIsANounAndAnAdjective() {
        String name = ExpeditionCatalog.name(new Random());
        assertTrue(name.matches("\\S+ \\S+"), name);
    }
}
