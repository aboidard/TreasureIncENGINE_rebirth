package org.bogomips.treasureInc.engine.item;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import org.bogomips.treasureInc.engine.item.ItemCatalog.Rarity;
import org.bogomips.treasureInc.engine.item.ItemService.ItemList;
import org.bogomips.treasureInc.engine.item.ItemService.SellResult;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;
import org.bogomips.treasureInc.engine.sync.CacheSyncService;
import org.bogomips.treasureInc.engine.user.*;
import org.bogomips.treasureInc.engine.user.UserService.CachedSession;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ItemEndpointsTest {

    private static final String PUB = "12345678900";
    private static final String PRIV = "secret";
    private static final int GRAPHICS_COUNT = 7;
    private static final int ID_BLOCK_SIZE = 4;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger dbReads = new AtomicInteger();
    private final AtomicInteger dbSells = new AtomicInteger();
    private final Map<String, CachedSession> users = new HashMap<>();
    private final Map<String, ItemList> itemCache = new HashMap<>();
    private final List<ItemInfo> dbItems = new ArrayList<>(List.of(
            new ItemInfo(1, "a", "d", 1, 100, "common", "t", "t"),
            new ItemInfo(2, "b", "d", 2, 250, "rare", "t", "t"),
            new ItemInfo(3, "c", "d", 3, 50, "common", "t", "t")));
    private int dbMoney = 1000;
    private int syncedMoney = -1;
    // the database sequence: database items above use ids 1..3
    private int dbSequence = 100;
    private final List<Integer> nextIdsCalls = new ArrayList<>();
    private final Deque<Integer> idPool = new ArrayDeque<>();

    private final ItemService.Repository itemRepo = new ItemService.Repository() {
        public Uni<ItemList> findByPublicKey(String pk) {
            dbReads.incrementAndGet();
            return Uni.createFrom().item(PUB.equals(pk) ? new ItemList(true, List.copyOf(dbItems)) : ItemList.unknownUser());
        }

        public Uni<List<Integer>> nextIds(int count) {
            nextIdsCalls.add(count);
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ids.add(++dbSequence);
            }
            return Uni.createFrom().item(ids);
        }

        public Uni<Void> insertAll(int userId, List<ItemInfo> items) {
            for (ItemInfo i : items) {
                dbItems.add(new ItemInfo(i.id(), i.name(), i.resolvedDescription(), i.graphics(), i.price(), i.rarity(), "t", "t"));
            }
            return Uni.createFrom().voidItem();
        }

        public Uni<Void> deleteByIds(int userId, List<Integer> ids) {
            dbItems.removeIf(i -> ids.contains(i.id()));
            return Uni.createFrom().voidItem();
        }

        public Uni<SellResult> sell(String pk, String priv, List<Integer> ids, int extra) {
            dbSells.incrementAndGet();
            if (!PUB.equals(pk) || !PRIV.equals(priv)) {
                return Uni.createFrom().item(SellResult.of(SellResult.Status.UNAUTHORIZED));
            }
            List<ItemInfo> sold = dbItems.stream().filter(i -> ids.contains(i.id())).toList();
            if (sold.size() != ids.size()) {
                return Uni.createFrom().item(SellResult.of(SellResult.Status.NOT_FOUND));
            }
            int total = sold.stream().mapToInt(ItemInfo::price).sum() + extra;
            dbItems.removeAll(sold);
            dbMoney += total;
            return Uni.createFrom().item(new SellResult(SellResult.Status.OK, total, dbMoney, ids));
        }
    };

    private final ItemService.Cache cache = new ItemService.Cache() {
        public Uni<ItemList> get(String pk) {
            return Uni.createFrom().item(itemCache.get(pk));
        }

        public Uni<Void> put(String pk, ItemList l) {
            itemCache.put(pk, l);
            return Uni.createFrom().voidItem();
        }

        public Uni<Void> persist(String pk, ItemList l) {
            itemCache.put(pk, l);
            return Uni.createFrom().voidItem();
        }

        public Uni<List<Integer>> takeIds(int count) {
            List<Integer> ids = new ArrayList<>();
            while (ids.size() < count && !idPool.isEmpty()) {
                ids.add(idPool.poll());
            }
            return Uni.createFrom().item(ids);
        }

        public Uni<Void> addIds(List<Integer> ids) {
            idPool.addAll(ids);
            return Uni.createFrom().voidItem();
        }

        public Uni<Void> evict(String k) {
            itemCache.remove(k);
            return Uni.createFrom().voidItem();
        }

        public Uni<Void> removeIds(String pk, Collection<Integer> ids) {
            ItemList l = itemCache.get(pk);
            if (l != null) {
                itemCache.put(pk, new ItemList(l.userFound(), l.items().stream().filter(i -> !ids.contains(i.id())).toList(),
                        l.pendingIds().stream().filter(id -> !ids.contains(id)).collect(java.util.stream.Collectors.toSet())));
            }
            return Uni.createFrom().voidItem();
        }
    };

    private final UserService.Cache userCache = new UserService.Cache() {
        public Uni<CachedSession> get(String k) {
            return Uni.createFrom().item(users.get(k));
        }

        public Uni<Void> evict(String k) {
            users.remove(k);
            return Uni.createFrom().voidItem();
        }

        public io.smallrye.mutiny.Multi<CachedSession> scan() {
            return io.smallrye.mutiny.Multi.createFrom().iterable(java.util.List.copyOf(users.values()));
        }

        public Uni<Void> put(String k, CachedSession s) {
            users.put(k, s);
            return Uni.createFrom().voidItem();
        }
    };

    private final UserService.Repository userRepo = new UserService.Repository() {
        public Uni<UserInfo> login(String pub, String priv) {
            return Uni.createFrom().nullItem();
        }

        public Uni<UserInfo> authenticate(String pub, String priv) {
            return Uni.createFrom().item(PUB.equals(pub) && PRIV.equals(priv)
                    ? new UserInfo(1, pub, dbMoney, "t", "t", "t") : null);
        }

        public Uni<Boolean> exists(String pub) {
            return Uni.createFrom().item(false);
        }

        public Uni<Void> updateMoney(int id, int money) {
            syncedMoney = money;
            return Uni.createFrom().voidItem();
        }

        public Uni<UserInfo> create(String pub, String priv, int money) {
            throw new UnsupportedOperationException();
        }
    };

    /** Single-threaded tests need no mutual exclusion; only records the keys it was asked to lock. */
    private final List<String> locked = new ArrayList<>();
    private final UserService.Lock lock = new UserService.Lock() {
        public <T> Uni<T> withLock(String publicKey, java.util.function.Supplier<Uni<T>> action) {
            locked.add(publicKey);
            return Uni.createFrom().deferred(action::get);
        }
    };
    private final UserService userService = new UserService(userRepo, userCache, lock, 10000);
    private final ItemService service = new ItemService(itemRepo, cache, userService, lock, GRAPHICS_COUNT, ID_BLOCK_SIZE);
    private final ItemEndpoints endpoints = new ItemEndpoints(service, userService);

    private JsonNode call(RequestHandler h, String json) throws Exception {
        return mapper.valueToTree(h.handle(mapper.readTree(json)).await().indefinitely());
    }

    @Test
    void publicGetPagesAndUsesCache() throws Exception {
        RequestHandler h = endpoints.getItems();
        JsonNode p1 = call(h, "{\"user\":\"" + PUB + "\",\"page\":1,\"limit\":2}");
        assertEquals(200, p1.get("status").asInt());
        assertEquals(2, p1.at("/payload/items").size());
        assertEquals(3, p1.at("/payload/total").asInt());
        JsonNode p2 = call(h, "{\"user\":\"" + PUB + "\",\"page\":2,\"limit\":2}");
        assertEquals(1, p2.at("/payload/items").size());
        assertEquals(3, p2.at("/payload/items/0/id").asInt());
        assertEquals(1, dbReads.get());
    }

    @Test
    void getValidatesInputAndUnknownUser() throws Exception {
        RequestHandler h = endpoints.getItems();
        assertEquals(400, call(h, "{\"user\":\"abc\"}").get("status").asInt());
        assertEquals(400, call(h, "{\"user\":\"" + PUB + "\",\"limit\":1000}").get("status").asInt());
        assertEquals(404, call(h, "{\"user\":\"99999999999\"}").get("status").asInt());
        call(h, "{\"user\":\"99999999999\"}");
        assertEquals(1, dbReads.get());
    }

    @Test
    void privateGetChecksKey() throws Exception {
        RequestHandler h = endpoints.getPrivateItems();
        assertEquals(401, call(h, "{\"user\":\"" + PUB + "\",\"private_key\":\"bad\"}").get("status").asInt());
        assertEquals(401, call(h, "{\"user\":\"" + PUB + "\"}").get("status").asInt());
        assertEquals(200, call(h, "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\"}").get("status").asInt());
    }

    @Test
    void generateStoresInRedisOnly() throws Exception {
        RequestHandler h = endpoints.generateItemsForUser();
        assertEquals(401, call(h, "{\"user\":\"" + PUB + "\",\"private_key\":\"bad\",\"nb\":2}").get("status").asInt());
        assertEquals(400, call(h, "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\",\"nb\":0}").get("status").asInt());

        JsonNode r = call(h, "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\",\"nb\":5}");
        assertEquals(200, r.get("status").asInt());
        assertEquals(5, r.at("/payload/items").size());
        for (JsonNode i : r.at("/payload/items")) {
            assertTrue(i.get("id").asInt() > 100, "id from the database sequence");
            assertTrue(i.get("description").isTextual());
            assertFalse(i.get("name").asText().isBlank());
            int graphics = i.get("graphics").asInt(-1);
            assertTrue(i.get("graphics").isInt() && graphics >= 0 && graphics < GRAPHICS_COUNT, "graphics " + i.get("graphics"));
        }
        assertEquals(8, itemCache.get(PUB).items().size());
        assertEquals(Set.of(101, 102, 103, 104, 105), itemCache.get(PUB).pendingIds());
        assertNull(itemCache.get(PUB).items().get(3).description());
        assertNotNull(itemCache.get(PUB).items().get(3).descriptionId());
        assertEquals(3, dbItems.size());
    }

    @Test
    void rarityTable() {
        assertEquals(Rarity.COMMON, Rarity.fromRoll(650));
        assertEquals(Rarity.UNCOMMON, Rarity.fromRoll(651));
        assertEquals(Rarity.RARE, Rarity.fromRoll(990));
        assertEquals(Rarity.EPIC, Rarity.fromRoll(999));
        assertEquals(Rarity.LEGENDARY, Rarity.fromRoll(1000));
    }

    @Test
    void syncWritesPendingChangesOncePastMaxAge() throws Exception {
        users.put(PUB, new CachedSession(new UserInfo(1, PUB, 1000, "t", "t", "t"), "old", CachedSession.hashKey(PRIV)));
        JsonNode gen = call(endpoints.generateItemsForUser(), "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\",\"nb\":2}");
        int id1 = gen.at("/payload/items/0/id").asInt();
        int id2 = gen.at("/payload/items/1/id").asInt();
        call(endpoints.sellItems(), "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\",\"items\":[{\"id\":" + id2 + "}]}");
        assertTrue(users.get(PUB).dirty());
        int soldPrice = gen.at("/payload/items/1/price").asInt();

        CacheSyncService sync = new CacheSyncService(userCache, cache, userRepo, itemRepo, lock, java.time.Duration.ofMinutes(3), java.time.Duration.ofHours(1));
        // too early: lastSync is "old"-based, so force a recent one
        users.put(PUB, users.get(PUB).synced(java.time.Instant.now(), true));
        assertEquals(0, sync.syncDue(java.time.Instant.now()).await().indefinitely());
        assertEquals(-1, syncedMoney);

        assertEquals(1, sync.syncDue(java.time.Instant.now().plusSeconds(181)).await().indefinitely());
        assertEquals(1000 + soldPrice, syncedMoney);
        assertFalse(users.get(PUB).dirty());
        // the unsold item is inserted with its id, which does not change; the sold one never reaches the database
        assertEquals(id1, itemCache.get(PUB).items().get(3).id());
        assertTrue(itemCache.get(PUB).pendingIds().isEmpty());
        assertTrue(dbItems.stream().anyMatch(i -> i.id() == id1));
        assertTrue(dbItems.stream().noneMatch(i -> i.id() == id2));

        // nothing pending anymore
        assertEquals(0, sync.syncDue(java.time.Instant.now().plusSeconds(1000)).await().indefinitely());
    }

    @Test
    void anItemCanBeSoldWithItsGenerationIdAfterTheSync() throws Exception {
        String justNow = java.time.Instant.now().toString();
        users.put(PUB, new CachedSession(new UserInfo(1, PUB, 1000, "t", "t", "t"), justNow, CachedSession.hashKey(PRIV)));
        JsonNode gen = call(endpoints.generateItemsForUser(), "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\",\"nb\":1}");
        int id = gen.at("/payload/items/0/id").asInt();
        CacheSyncService sync = new CacheSyncService(userCache, cache, userRepo, itemRepo, lock,
                java.time.Duration.ofMinutes(3), java.time.Duration.ofHours(1));
        assertEquals(1, sync.syncDue(java.time.Instant.now().plusSeconds(181)).await().indefinitely());

        // the client never heard of the sync and still uses the id it got at generation
        JsonNode r = call(endpoints.sellItems(), "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\",\"items\":[{\"id\":" + id + "}]}");
        assertEquals(200, r.get("status").asInt());
        assertEquals(1, dbSells.get(), "the item is in the database now, so the sale goes through it");
        assertTrue(dbItems.stream().noneMatch(i -> i.id() == id));
    }

    @Test
    void idsAreReservedFromTheDatabaseByBlock() throws Exception {
        users.put(PUB, new CachedSession(new UserInfo(1, PUB, 1000, "t", "t", "t"), "old", CachedSession.hashKey(PRIV)));
        RequestHandler gen = endpoints.generateItemsForUser();
        String request = "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\",\"nb\":%d}";

        call(gen, request.formatted(3));
        assertEquals(List.of(ID_BLOCK_SIZE), nextIdsCalls, "a first block is reserved");
        assertEquals(List.of(104), List.copyOf(idPool), "what is not used now stays in the pool");

        call(gen, request.formatted(1));
        assertEquals(1, nextIdsCalls.size(), "served from the pool, no database call");

        call(gen, request.formatted(2));
        assertEquals(List.of(ID_BLOCK_SIZE, ID_BLOCK_SIZE), nextIdsCalls, "pool empty: a new block");

        // 2 ids left in the pool, 9 needed: the 7 missing ones are reserved at once, more than a block
        call(gen, request.formatted(9));
        assertEquals(List.of(ID_BLOCK_SIZE, ID_BLOCK_SIZE, 7), nextIdsCalls);
        assertTrue(idPool.isEmpty());

        List<Integer> ids = itemCache.get(PUB).items().stream().map(ItemInfo::id).filter(i -> i > 100).toList();
        assertEquals(15, ids.size());
        assertEquals(15, Set.copyOf(ids).size(), "every id is unique");
    }

    @Test
    void idleSyncedSessionsAreEvictedButPendingOnesAreKept() throws Exception {
        java.time.Instant longAgo = java.time.Instant.now().minus(java.time.Duration.ofHours(3));
        UserInfo u = new UserInfo(1, PUB, 1000, "t", "t", "t");
        UserInfo other = new UserInfo(2, "00000000002", 5, "t", "t", "t");
        users.put(PUB, new CachedSession(u, longAgo.toString(), "h"));
        users.put("00000000002", new CachedSession(other, longAgo.toString(), "h", longAgo.toString(), true));
        itemCache.put(PUB, new ItemList(true, java.util.List.of()));

        CacheSyncService sync = new CacheSyncService(userCache, cache, userRepo, itemRepo, lock,
                java.time.Duration.ofMinutes(3), java.time.Duration.ofHours(1));
        assertEquals(1, sync.syncDue(java.time.Instant.now()).await().indefinitely());

        assertNull(users.get("00000000002"), "dirty session is synced first, then idle => evicted");
        assertNull(users.get(PUB));
        assertNull(itemCache.get(PUB));
        assertEquals(5, syncedMoney);
    }

    @Test
    void sellAndGenerateRunUnderTheUserLock() throws Exception {
        users.put(PUB, new CachedSession(new UserInfo(1, PUB, 1000, "t", "t", "t"), "old", CachedSession.hashKey(PRIV)));
        call(endpoints.generateItemsForUser(), "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\",\"nb\":1}");
        call(endpoints.sellItems(), "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\",\"items\":[{\"id\":3}]}");
        assertEquals(java.util.List.of(PUB, PUB), locked);
    }

    @Test
    void cachedMoneyIsNotOverwrittenByDatabaseSale() throws Exception {
        users.put(PUB, new CachedSession(new UserInfo(1, PUB, 1000, "t", "t", "t"), "old", CachedSession.hashKey(PRIV)));
        JsonNode gen = call(endpoints.generateItemsForUser(), "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\",\"nb\":1}");
        int id = gen.at("/payload/items/0/id").asInt();
        int price = gen.at("/payload/items/0/price").asInt();
        call(endpoints.sellItems(), "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\",\"items\":[{\"id\":" + id + "}]}");
        assertEquals(1000 + price, users.get(PUB).user().money());

        JsonNode r = call(endpoints.sellItems(), "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\",\"items\":[{\"id\":3}]}");
        assertEquals(1000 + price + 50, r.at("/payload/money").asInt());
        assertEquals(1000 + price + 50, users.get(PUB).user().money());
    }

    @Test
    void sellGeneratedItemsStaysInRedis() throws Exception {
        users.put(PUB, new CachedSession(new UserInfo(1, PUB, 1000, "t", "t", "t"), "old", CachedSession.hashKey(PRIV)));
        JsonNode gen = call(endpoints.generateItemsForUser(), "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\",\"nb\":2}");
        int id1 = gen.at("/payload/items/0/id").asInt();
        int id2 = gen.at("/payload/items/1/id").asInt();
        int expected = gen.at("/payload/items/0/price").asInt() + gen.at("/payload/items/1/price").asInt();
        int reads = dbReads.get();

        JsonNode r = call(endpoints.sellItems(),
                "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\",\"items\":[{\"id\":" + id1 + "},{\"id\":" + id2 + "}]}");
        assertEquals(200, r.get("status").asInt());
        assertEquals(expected, r.at("/payload/totalPrice").asInt());
        assertEquals(1000 + expected, r.at("/payload/money").asInt());
        assertEquals(1000 + expected, users.get(PUB).user().money());
        assertEquals(0, dbSells.get());
        assertEquals(reads, dbReads.get());
        assertEquals(3, itemCache.get(PUB).items().size());
    }

    @Test
    void sellMixedAddsRedisValueInOneDbCall() throws Exception {
        users.put(PUB, new CachedSession(new UserInfo(1, PUB, 1000, "t", "t", "t"), "old", CachedSession.hashKey(PRIV)));
        JsonNode gen = call(endpoints.generateItemsForUser(), "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\",\"nb\":1}");
        int id = gen.at("/payload/items/0/id").asInt();
        int price = gen.at("/payload/items/0/price").asInt();

        JsonNode r = call(endpoints.sellItems(),
                "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\",\"items\":[{\"id\":" + id + "},{\"id\":3}]}");
        assertEquals(200, r.get("status").asInt());
        assertEquals(50 + price, r.at("/payload/totalPrice").asInt());
        assertEquals(1, dbSells.get());
    }

    @Test
    void sellRemovesFromCacheAndCreditsMoney() throws Exception {
        users.put(PUB, new CachedSession(new UserInfo(1, PUB, 1000, "t", "t", "t"), "old", CachedSession.hashKey(PRIV)));
        RequestHandler get = endpoints.getItems();
        call(get, "{\"user\":\"" + PUB + "\"}");

        RequestHandler sell = endpoints.sellItems();
        JsonNode r = call(sell, "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\",\"items\":[{\"id\":1},{\"id\":2},{\"id\":2}]}");
        assertEquals(200, r.get("status").asInt());
        assertEquals(350, r.at("/payload/totalPrice").asInt());
        assertEquals(1350, r.at("/payload/money").asInt());
        assertEquals(1350, users.get(PUB).user().money());
        assertEquals(List.of(3), itemCache.get(PUB).items().stream().map(ItemInfo::id).toList());

        assertEquals(404, call(sell, "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\",\"items\":[{\"id\":1}]}").get("status").asInt());
        assertEquals(401, call(sell, "{\"user\":\"" + PUB + "\",\"private_key\":\"bad\",\"items\":[{\"id\":3}]}").get("status").asInt());
        assertEquals(400, call(sell, "{\"user\":\"" + PUB + "\",\"private_key\":\"secret\",\"items\":[]}").get("status").asInt());
    }
}
