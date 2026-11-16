package org.bogomips.treasureInc.engine.quest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.bogomips.treasureInc.engine.item.ItemInfo;
import org.bogomips.treasureInc.engine.item.ItemService;
import org.bogomips.treasureInc.engine.item.ItemService.ItemList;
import org.bogomips.treasureInc.engine.item.ItemService.SellResult;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;
import org.bogomips.treasureInc.engine.quest.QuestBoards.State;
import org.bogomips.treasureInc.engine.quest.QuestCatalog.Definition;
import org.bogomips.treasureInc.engine.quest.QuestCatalog.Metric;
import org.bogomips.treasureInc.engine.quest.QuestCatalog.Scope;
import org.bogomips.treasureInc.engine.user.UserInfo;
import org.bogomips.treasureInc.engine.user.UserService;
import org.bogomips.treasureInc.engine.user.UserService.CachedSession;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class QuestEndpointsTest {

    private static final String PUB = "12345678900";
    private static final String PRIV = "secret";
    private static final int START_MONEY = 1000;
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    /** A Wednesday, 22:30 in Paris. */
    private static final Instant WEDNESDAY_EVENING = Instant.parse("2026-11-18T21:30:00Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedSession> sessions = new HashMap<>();
    private final Map<String, ItemList> itemLists = new HashMap<>();
    private final Map<String, State> boardsByUser = new HashMap<>();
    private int sequence = 100;
    private Instant now = WEDNESDAY_EVENING;

    private final Clock clock = new Clock() {
        public ZoneId getZone() {
            return PARIS;
        }

        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        public Instant instant() {
            return now;
        }
    };

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

    private final UserService.Lock lock = new UserService.Lock() {
        public <T> Uni<T> withLock(String publicKey, Supplier<Uni<T>> action) {
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
            ItemList list = itemLists.get(publicKey);
            itemLists.put(publicKey, new ItemList(true,
                    list.items().stream().filter(i -> !ids.contains(i.id())).toList(),
                    list.pendingIds().stream().filter(id -> !ids.contains(id)).collect(
                            java.util.stream.Collectors.toSet())));
            return Uni.createFrom().voidItem();
        }

        public Uni<Void> evict(String publicKey) {
            itemLists.remove(publicKey);
            return Uni.createFrom().voidItem();
        }
    };

    private final QuestBoards.Store store = new QuestBoards.Store() {
        public Uni<State> get(String publicKey) {
            return Uni.createFrom().item(boardsByUser.get(publicKey));
        }

        public Uni<Void> put(String publicKey, State state) {
            boardsByUser.put(publicKey, state);
            return Uni.createFrom().voidItem();
        }
    };

    private final UserService users = new UserService(userRepo, userCache, lock, 10000);
    private final QuestBoards boards = new QuestBoards(store, 3, 3, clock, new Random(7));
    private final ItemService items = new ItemService(itemRepo, itemCache, users, lock,
            publicKey -> Uni.createFrom().item(Set.of()), 7, 4, 0, boards);
    private final QuestEndpoints endpoints = new QuestEndpoints(new QuestService(boards, users, items, lock));

    private JsonNode call(RequestHandler handler, String privateKey, String fields) throws Exception {
        JsonNode params = mapper.readTree("{\"user\":\"" + PUB + "\",\"private_key\":\"" + privateKey + "\""
                + fields + "}");
        return mapper.valueToTree(handler.handle(params).await().indefinitely());
    }

    private JsonNode quests() throws Exception {
        JsonNode r = call(endpoints.getQuests(), PRIV, "");
        assertEquals(200, r.get("status").asInt());
        return r.get("payload");
    }

    private JsonNode claim(String questId) throws Exception {
        return call(endpoints.claimQuest(), PRIV, ",\"quest\":\"" + questId + "\"");
    }

    private void count(Metric metric, int amount) {
        boards.addLocked(PUB, Map.of(metric, amount)).await().indefinitely();
    }

    /** Counts every metric: whatever was drawn moves forward. */
    private void countEverything(int amount) {
        Map<Metric, Integer> amounts = new HashMap<>();
        for (Metric metric : Metric.values()) {
            amounts.put(metric, amount);
        }
        boards.addLocked(PUB, amounts).await().indefinitely();
    }

    private static JsonNode quest(JsonNode payload, String id) {
        for (String scope : List.of("daily", "weekly")) {
            for (JsonNode quest : payload.at("/" + scope + "/quests")) {
                if (id.equals(quest.get("id").asText())) {
                    return quest;
                }
            }
        }
        return null;
    }

    // ---- getQuests

    @Test
    void threeDailyAndThreeWeeklyQuestsAreDrawn() throws Exception {
        JsonNode payload = quests();
        assertEquals(0, payload.get("claimable").asInt());
        assertEquals("2026-11-18", payload.at("/daily/period").asText());
        assertEquals("2026-W47", payload.at("/weekly/period").asText());
        // 22:30 on a Wednesday: an hour and a half before the day ends, four days more before the week does
        assertEquals(90 * 60, payload.at("/daily/resetIn").asLong());
        assertEquals(90 * 60 + 4 * 24 * 3600, payload.at("/weekly/resetIn").asLong());

        for (Scope scope : Scope.values()) {
            Set<String> metrics = new HashSet<>();
            JsonNode drawn = payload.at("/" + scope.key() + "/quests");
            assertEquals(3, drawn.size());
            for (JsonNode quest : drawn) {
                Definition definition = QuestCatalog.get(quest.get("id").asText());
                assertEquals(scope, definition.scope());
                assertEquals(definition.metric().key(), quest.get("metric").asText());
                assertEquals(definition.goal(), quest.get("goal").asInt());
                assertEquals(definition.money(), quest.at("/reward/money").asInt());
                assertEquals(definition.items(), quest.at("/reward/items").asInt());
                assertEquals(0, quest.get("progress").asInt());
                assertFalse(quest.get("completed").asBoolean());
                assertFalse(quest.get("claimed").asBoolean());
                metrics.add(quest.get("metric").asText());
            }
            assertEquals(3, metrics.size(), "the quests of a board count different things");
        }
        assertEquals(payload, quests(), "the boards do not change when they are read again");
    }

    @Test
    void theCredentialsAreChecked() throws Exception {
        assertEquals(401, call(endpoints.getQuests(), "wrong", "").get("status").asInt());
        assertEquals(400, call(endpoints.getQuests(), "", "").get("status").asInt());
        assertEquals(401, call(endpoints.claimQuest(), "wrong", ",\"quest\":\"daily_items\"").get("status").asInt());
        assertEquals(400, call(endpoints.claimQuest(), PRIV, "").get("status").asInt());
        assertTrue(boardsByUser.isEmpty(), "nothing is drawn for a stranger");
    }

    @Test
    void aWeeklyQuestAsksForMoreThanTheDailyOneAndPaysMore() {
        for (Definition daily : QuestCatalog.of(Scope.DAILY)) {
            Definition weekly = QuestCatalog.get("weekly_" + daily.metric().key());
            assertNotNull(weekly, daily.id());
            assertTrue(weekly.goal() > daily.goal(), daily.id());
            assertTrue(weekly.money() > daily.money(), daily.id());
            assertTrue(weekly.items() > daily.items(), daily.id());
        }
        assertEquals(Metric.values().length, QuestCatalog.of(Scope.DAILY).size());
        assertEquals(Metric.values().length, QuestCatalog.of(Scope.WEEKLY).size());
    }

    // ---- progress

    @Test
    void whatThePlayerDoesMovesItsQuestsForward() throws Exception {
        JsonNode daily = quests().at("/daily/quests/0");
        String id = daily.get("id").asText();
        Metric metric = QuestCatalog.get(id).metric();
        int goal = daily.get("goal").asInt();

        count(metric, 1);
        JsonNode payload = quests();
        assertEquals(1, quest(payload, id).get("progress").asInt());
        assertFalse(quest(payload, id).get("completed").asBoolean());
        assertEquals(0, payload.get("claimable").asInt());

        count(metric, goal + 50);
        payload = quests();
        assertEquals(goal, quest(payload, id).get("progress").asInt(), "the progress stops at the goal");
        assertTrue(quest(payload, id).get("completed").asBoolean());
        assertEquals(1, payload.get("claimable").asInt());
        JsonNode weekly = quest(payload, "weekly_" + metric.key());
        if (weekly != null) {
            assertEquals(goal + 51, weekly.get("progress").asInt(), "the weekly quest counts the same actions");
        }
    }

    @Test
    void progressCountsEvenBeforeTheQuestsWereEverRead() throws Exception {
        countEverything(1);
        for (JsonNode quest : quests().at("/daily/quests")) {
            assertEquals(1, quest.get("progress").asInt());
        }
    }

    @Test
    void sellingItemsCountsForTheQuests() throws Exception {
        List<ItemInfo> owned = new ArrayList<>();
        for (int id = 1; id <= 4; id++) {
            owned.add(new ItemInfo(id, "objet", "d", 1, 10, "common", "t", "t"));
        }
        // the items only live in Redis so far, like those of a chest that was just opened
        itemLists.put(PUB, new ItemList(true, owned, Set.of(1, 2, 3, 4)));
        users.login(PUB, PRIV).await().indefinitely();
        boardsByUser.put(PUB, new State(
                new QuestBoards.Board("2026-11-18", List.of(new QuestBoards.Entry("daily_sales", 0, false))),
                new QuestBoards.Board("2026-W47", List.of())));

        assertEquals(SellResult.Status.OK, items.sell(PUB, PRIV, List.of(1, 2, 3)).await().indefinitely().status());
        assertEquals(3, quest(quests(), "daily_sales").get("progress").asInt());
        assertEquals(SellResult.Status.NOT_FOUND, items.sell(PUB, PRIV, List.of(1)).await().indefinitely().status());
        assertEquals(3, quest(quests(), "daily_sales").get("progress").asInt());
    }

    // ---- claimQuest

    @Test
    void claimingACompletedQuestGivesMoneyAndItemsOnce() throws Exception {
        String id = quests().at("/daily/quests/1/id").asText();
        Definition definition = QuestCatalog.get(id);
        assertEquals(409, claim(id).get("status").asInt(), "not completed yet");
        assertEquals(START_MONEY, sessions.get(PUB).user().money());

        count(definition.metric(), definition.goal());
        JsonNode r = claim(id);
        assertEquals(200, r.get("status").asInt());
        assertEquals(id, r.at("/payload/quest").asText());
        assertEquals(START_MONEY + definition.money(), r.at("/payload/money").asInt());
        assertEquals(definition.money(), r.at("/payload/reward/money").asInt());
        assertEquals(definition.items(), r.at("/payload/reward/items").size());
        assertTrue(r.at("/payload/reward/items/0/id").asInt() > 100);
        assertTrue(quest(r.at("/payload/quests"), id).get("claimed").asBoolean());
        assertEquals(0, r.at("/payload/quests/claimable").asInt());

        assertEquals(START_MONEY + definition.money(), sessions.get(PUB).user().money());
        assertTrue(sessions.get(PUB).dirty(), "the balance waits for the synchronization");
        assertEquals(definition.items(), itemLists.get(PUB).items().size());
        assertEquals(definition.items(), itemLists.get(PUB).pendingIds().size());

        assertEquals(409, claim(id).get("status").asInt(), "already claimed");
        assertEquals(START_MONEY + definition.money(), sessions.get(PUB).user().money());
        assertEquals(definition.items(), itemLists.get(PUB).items().size());
    }

    @Test
    void aQuestThatIsNotOnTheBoardsCannotBeClaimed() throws Exception {
        Set<String> drawn = new HashSet<>();
        quests().at("/daily/quests").forEach(q -> drawn.add(q.get("id").asText()));
        String absent = QuestCatalog.of(Scope.DAILY).stream().map(Definition::id)
                .filter(id -> !drawn.contains(id)).findFirst().orElseThrow();
        countEverything(1000);
        assertEquals(404, claim(absent).get("status").asInt());
        assertEquals(404, claim("nope").get("status").asInt());
    }

    // ---- periods

    @Test
    void aNewDayDrawsNewDailyQuestsAndKeepsTheWeeklyOnes() throws Exception {
        countEverything(1000);
        JsonNode before = quests();
        assertEquals(6, before.get("claimable").asInt());

        now = now.plus(Duration.ofHours(2));
        JsonNode after = quests();
        assertEquals("2026-11-19", after.at("/daily/period").asText());
        for (JsonNode quest : after.at("/daily/quests")) {
            assertEquals(0, quest.get("progress").asInt());
        }
        assertEquals(before.at("/weekly/quests"), after.at("/weekly/quests"));
        assertEquals(3, after.get("claimable").asInt(), "what was not claimed yesterday is lost");
        String old = before.at("/daily/quests/0/id").asText();
        if (quest(after, old) == null) {
            assertEquals(404, claim(old).get("status").asInt());
        } else {
            assertEquals(409, claim(old).get("status").asInt());
        }
    }

    @Test
    void aNewWeekStartsOnMondayAtMidnight() throws Exception {
        countEverything(1000);
        quests();
        // Sunday 23:30 in Paris
        now = Instant.parse("2026-11-22T22:30:00Z");
        assertEquals("2026-W47", quests().at("/weekly/period").asText());
        assertEquals(30 * 60, quests().at("/weekly/resetIn").asLong());
        assertEquals(3, quests().get("claimable").asInt());

        now = now.plus(Duration.ofHours(1));
        JsonNode monday = quests();
        assertEquals("2026-W48", monday.at("/weekly/period").asText());
        assertEquals("2026-11-23", monday.at("/daily/period").asText());
        assertEquals(0, monday.get("claimable").asInt());
    }
}
