package org.bogomips.treasureInc.engine.gift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.bogomips.treasureInc.engine.gift.GiftService.ClaimOutcome;
import org.bogomips.treasureInc.engine.gift.GiftService.GiftInfo;
import org.bogomips.treasureInc.engine.gift.GiftService.Pending;
import org.bogomips.treasureInc.engine.gift.GiftService.Reward;
import org.bogomips.treasureInc.engine.gift.GiftService.Target;
import org.bogomips.treasureInc.engine.item.ItemInfo;
import org.bogomips.treasureInc.engine.item.ItemService;
import org.bogomips.treasureInc.engine.item.ItemService.ItemList;
import org.bogomips.treasureInc.engine.item.ItemService.SellResult;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;
import org.bogomips.treasureInc.engine.user.UserInfo;
import org.bogomips.treasureInc.engine.user.UserService;
import org.bogomips.treasureInc.engine.user.UserService.CachedSession;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class GiftEndpointsTest {

    private static final String PUB = "12345678900";
    private static final String PRIV = "secret";
    private static final int USER_ID = 1;
    private static final int OTHER_USER = 2;
    private static final int START_MONEY = 1000;
    private static final int START_GEMS = 50;
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedSession> sessions = new HashMap<>();
    private final Map<String, ItemList> itemLists = new HashMap<>();
    private int sequence = 100;

    /** The gifts table: claimed ones stay, with their claim time. */
    private final List<StoredGift> table = new ArrayList<>();
    private Sent lastSent;

    private record StoredGift(int userId, GiftInfo gift, Instant expiresAt, Instant claimedAt) {
    }

    private record Sent(Target target, String title, Reward reward, Duration expiresIn, String batch) {
    }

    private final GiftService.Repository giftRepo = new GiftService.Repository() {
        public Uni<Pending> pending(int userId, Instant now, int limit) {
            List<GiftInfo> waiting = table.stream()
                    .filter(g -> g.userId() == userId && g.claimedAt() == null
                            && (g.expiresAt() == null || g.expiresAt().isAfter(now)))
                    .map(StoredGift::gift)
                    .sorted(Comparator.comparingInt(GiftInfo::id).reversed())
                    .toList();
            return Uni.createFrom().item(new Pending(waiting.size(), waiting.stream().limit(limit).toList()));
        }

        public Uni<ClaimOutcome> claim(int userId, int giftId, Instant now) {
            for (int i = 0; i < table.size(); i++) {
                StoredGift g = table.get(i);
                if (g.gift().id() != giftId || g.userId() != userId) {
                    continue;
                }
                if (g.claimedAt() != null) {
                    return Uni.createFrom().item(new ClaimOutcome(ClaimOutcome.Status.ALREADY_CLAIMED, null));
                }
                if (g.expiresAt() != null && !g.expiresAt().isAfter(now)) {
                    return Uni.createFrom().item(new ClaimOutcome(ClaimOutcome.Status.EXPIRED, null));
                }
                table.set(i, new StoredGift(userId, g.gift(), g.expiresAt(), now));
                return Uni.createFrom().item(new ClaimOutcome(ClaimOutcome.Status.OK, g.gift()));
            }
            return Uni.createFrom().item(new ClaimOutcome(ClaimOutcome.Status.NOT_FOUND, null));
        }

        public Uni<Integer> send(Target target, String title, Reward reward, Duration expiresIn, String batch,
                                 Instant now) {
            lastSent = new Sent(target, title, reward, expiresIn, batch);
            return Uni.createFrom().item(42);
        }
    };

    private final UserService.Repository userRepo = new UserService.Repository() {
        public Uni<UserInfo> login(String pub, String priv) {
            return authenticate(pub, priv);
        }

        public Uni<UserInfo> authenticate(String pub, String priv) {
            return Uni.createFrom().item(PUB.equals(pub) && PRIV.equals(priv)
                    ? new UserInfo(USER_ID, pub, START_MONEY, START_GEMS, "t", "t", "t") : null);
        }

        public Uni<Boolean> exists(String pub) {
            return Uni.createFrom().item(false);
        }

        public Uni<Void> updateBalance(int id, int money, int gems) {
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
            throw new UnsupportedOperationException();
        }

        public Uni<Void> evict(String publicKey) {
            itemLists.remove(publicKey);
            return Uni.createFrom().voidItem();
        }
    };

    private final UserService users = new UserService(userRepo, userCache, lock, 10000);
    private final ItemService items = new ItemService(itemRepo, itemCache, users, lock,
            publicKey -> Uni.createFrom().item(Set.of()), 7, 4, 0);
    private final GiftService service = new GiftService(giftRepo, users, items, lock, 100,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final GiftEndpoints endpoints = new GiftEndpoints(service);

    private void give(int id, int userId, Reward reward, Instant expiresAt) {
        table.add(new StoredGift(userId, new GiftInfo(id, "Cadeau " + id, reward, "2026-11-19T10:00:00Z",
                expiresAt == null ? null : expiresAt.toString()), expiresAt, null));
    }

    private JsonNode call(RequestHandler handler, String json) throws Exception {
        return mapper.valueToTree(handler.handle(mapper.readTree(json)).await().indefinitely());
    }

    private JsonNode gifts(String privateKey) throws Exception {
        return call(endpoints.getGifts(), "{\"user\":\"" + PUB + "\",\"private_key\":\"" + privateKey + "\"}");
    }

    private JsonNode claim(Object giftId) throws Exception {
        String id = giftId instanceof String s ? "\"" + s + "\"" : String.valueOf(giftId);
        return call(endpoints.claimGift(), "{\"user\":\"" + PUB + "\",\"private_key\":\"" + PRIV + "\",\"gift\":" + id + "}");
    }

    private JsonNode send(String params) throws Exception {
        lastSent = null;
        return call(endpoints.sendGift(), params);
    }

    // ---- getGifts

    @Test
    void theBoxHoldsTheGiftsWaitingTheLatestFirst() throws Exception {
        give(1, USER_ID, new Reward(0, 1000, 0, null), null);
        give(2, USER_ID, new Reward(500, 0, 3, "epic"), NOW.plus(Duration.ofDays(2)));
        give(3, OTHER_USER, new Reward(0, 10, 0, null), null);
        give(4, USER_ID, new Reward(0, 10, 0, null), NOW.minusSeconds(1));

        JsonNode r = gifts(PRIV);
        assertEquals(200, r.get("status").asInt());
        assertEquals(2, r.at("/payload/count").asInt(), "neither the gift of another player nor an expired one");
        JsonNode list = r.at("/payload/gifts");
        assertEquals(2, list.at("/0/id").asInt());
        assertEquals("Cadeau 2", list.at("/0/title").asText());
        assertEquals(500, list.at("/0/reward/money").asInt());
        assertEquals(0, list.at("/0/reward/gems").asInt());
        assertEquals(3, list.at("/0/reward/items").asInt());
        assertEquals("epic", list.at("/0/reward/rarity").asText());
        assertEquals("2026-11-22T10:00:00Z", list.at("/0/expiresAt").asText());
        assertEquals(1, list.at("/1/id").asInt());
        assertTrue(list.at("/1/reward/rarity").isNull());
        assertTrue(list.at("/1/expiresAt").isNull());
    }

    @Test
    void theCredentialsAreChecked() throws Exception {
        assertEquals(401, gifts("wrong").get("status").asInt());
        assertEquals(400, call(endpoints.getGifts(), "{\"user\":\"" + PUB + "\"}").get("status").asInt());
        assertEquals(401, call(endpoints.claimGift(), "{\"user\":\"" + PUB + "\",\"private_key\":\"wrong\",\"gift\":1}")
                .get("status").asInt());
        assertEquals(400, claim("1").get("status").asInt(), "the id of a gift is a number");
        assertEquals(400, call(endpoints.claimGift(), "{\"user\":\"" + PUB + "\",\"private_key\":\"" + PRIV + "\"}")
                .get("status").asInt());
    }

    // ---- claimGift

    @Test
    void claimingAGiftGivesItsRewardOnce() throws Exception {
        give(1, USER_ID, new Reward(500, 1000, 3, "legendary"), null);
        give(2, USER_ID, new Reward(0, 10, 0, null), null);

        JsonNode r = claim(1);
        assertEquals(200, r.get("status").asInt());
        assertEquals(1, r.at("/payload/gift").asInt());
        assertEquals("Cadeau 1", r.at("/payload/title").asText());
        assertEquals(START_MONEY + 500, r.at("/payload/money").asInt());
        assertEquals(START_GEMS + 1000, r.at("/payload/gems").asInt());
        assertEquals(500, r.at("/payload/reward/money").asInt());
        assertEquals(1000, r.at("/payload/reward/gems").asInt());
        JsonNode given = r.at("/payload/reward/items");
        assertEquals(3, given.size());
        for (JsonNode item : given) {
            assertEquals("legendary", item.get("rarity").asText(), "the rarity of the gift");
        }
        assertEquals(1, r.at("/payload/gifts/count").asInt(), "what is left in the box");
        assertEquals(2, r.at("/payload/gifts/gifts/0/id").asInt());

        assertEquals(START_MONEY + 500, sessions.get(PUB).user().money());
        assertEquals(START_GEMS + 1000, sessions.get(PUB).user().gems());
        assertTrue(sessions.get(PUB).dirty(), "the balances wait for the synchronization");
        assertEquals(3, itemLists.get(PUB).pendingIds().size());

        assertEquals(409, claim(1).get("status").asInt(), "already taken");
        assertEquals(START_GEMS + 1000, sessions.get(PUB).user().gems());
        assertEquals(3, itemLists.get(PUB).items().size());
    }

    @Test
    void aGiftWithoutRarityDrawsItsItems() throws Exception {
        give(1, USER_ID, new Reward(0, 0, 5, null), null);
        JsonNode r = claim(1);
        assertEquals(200, r.get("status").asInt());
        assertEquals(5, r.at("/payload/reward/items").size());
        assertEquals(START_GEMS, r.at("/payload/gems").asInt());
    }

    @Test
    void onlyAGiftOfTheUserStillValidCanBeClaimed() throws Exception {
        give(1, OTHER_USER, new Reward(0, 1000, 0, null), null);
        give(2, USER_ID, new Reward(0, 1000, 0, null), NOW);
        assertEquals(404, claim(1).get("status").asInt(), "the gift of another player");
        assertEquals(404, claim(99).get("status").asInt());
        assertEquals(410, claim(2).get("status").asInt(), "expired");
        assertEquals(START_GEMS, users.authenticate(PUB, PRIV).await().indefinitely().gems());
    }

    // ---- sendGift

    @Test
    void aGiftIsSentToThePlayersMatchingEveryCriterion() throws Exception {
        JsonNode r = send("{\"title\":\"  Lancement du jeu : profitez de ces gemmes gratuites !  \","
                + "\"reward\":{\"gems\":1000,\"money\":5000,\"items\":2,\"rarity\":\"rare\"},"
                + "\"players\":{\"activeDays\":7,\"createdDays\":30,"
                + "\"publicKeys\":[\"12345678900\",\"12345678901\",\"12345678900\"]},\"expiresInDays\":14}");
        assertEquals(200, r.get("status").asInt());
        assertEquals(42, r.at("/payload/sent").asInt());
        assertEquals(lastSent.batch(), r.at("/payload/batch").asText());
        assertEquals("Lancement du jeu : profitez de ces gemmes gratuites !", lastSent.title());
        assertEquals(new Reward(5000, 1000, 2, "rare"), lastSent.reward());
        assertEquals(new Target(7, null, 30, List.of("12345678900", "12345678901")), lastSent.target());
        assertEquals(Duration.ofDays(14), lastSent.expiresIn());
    }

    @Test
    void withoutPlayersAGiftGoesToEveryoneAndNeverExpires() throws Exception {
        JsonNode r = send("{\"title\":\"Bienvenue\",\"reward\":{\"gems\":100}}");
        assertEquals(200, r.get("status").asInt());
        assertEquals(Target.EVERYONE, lastSent.target());
        assertEquals(new Reward(0, 100, 0, null), lastSent.reward());
        assertNull(lastSent.expiresIn());

        assertEquals(200, send("{\"title\":\"Bienvenue\",\"reward\":{\"items\":1},\"players\":{}}").get("status").asInt());
        assertEquals(Target.EVERYONE, lastSent.target());
    }

    @Test
    void anInvalidGiftIsRefused() throws Exception {
        String reward = "\"reward\":{\"gems\":100}";
        List<String> invalid = List.of(
                "{" + reward + "}",
                "{\"title\":\"   \"," + reward + "}",
                "{\"title\":\"" + "x".repeat(GiftEndpoints.MAX_TITLE + 1) + "\"," + reward + "}",
                "{\"title\":\"t\"}",
                "{\"title\":\"t\",\"reward\":{}}",
                "{\"title\":\"t\",\"reward\":{\"gems\":0,\"money\":0,\"items\":0}}",
                "{\"title\":\"t\",\"reward\":{\"gems\":-5,\"money\":100}}",
                "{\"title\":\"t\",\"reward\":{\"gems\":\"100\"}}",
                "{\"title\":\"t\",\"reward\":{\"gems\":1.5}}",
                "{\"title\":\"t\",\"reward\":{\"gems\":" + (Reward.MAX_AMOUNT + 1) + "}}",
                "{\"title\":\"t\",\"reward\":{\"items\":" + (Reward.MAX_ITEMS + 1) + "}}",
                "{\"title\":\"t\",\"reward\":{\"items\":2,\"rarity\":\"mythic\"}}",
                "{\"title\":\"t\",\"reward\":{\"gems\":10,\"rarity\":\"epic\"}}",
                "{\"title\":\"t\"," + reward + ",\"players\":{\"activeDays\":0}}",
                "{\"title\":\"t\"," + reward + ",\"players\":{\"inactiveDays\":\"7\"}}",
                "{\"title\":\"t\"," + reward + ",\"players\":{\"createdDays\":" + (GiftEndpoints.MAX_DAYS + 1) + "}}",
                "{\"title\":\"t\"," + reward + ",\"players\":{\"publicKeys\":[]}}",
                "{\"title\":\"t\"," + reward + ",\"players\":{\"publicKeys\":[\"123\"]}}",
                "{\"title\":\"t\"," + reward + ",\"players\":{\"publicKeys\":\"12345678900\"}}",
                "{\"title\":\"t\"," + reward + ",\"players\":[]}",
                "{\"title\":\"t\"," + reward + ",\"expiresInDays\":0}");
        for (String params : invalid) {
            JsonNode r = send(params);
            assertEquals(400, r.get("status").asInt(), params);
            assertTrue(r.get("message").asText().startsWith("Bad Request: "), params);
            assertNull(lastSent, "nothing is sent: " + params);
        }
    }
}
