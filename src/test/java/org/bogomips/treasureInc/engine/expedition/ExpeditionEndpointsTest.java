package org.bogomips.treasureInc.engine.expedition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.bogomips.treasureInc.engine.user.UserInfo;
import org.bogomips.treasureInc.engine.user.UserService;
import org.bogomips.treasureInc.engine.user.UserService.CachedSession;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ExpeditionEndpointsTest {

    private static final String PUB = "12345678900";
    private static final String PRIV = "secret";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedSession> store = new HashMap<>();

    /** Only the credentials check is used: the session is opened from the database on the first call. */
    private final UserService.Repository repo = new UserService.Repository() {
        public Uni<UserInfo> login(String pub, String priv) {
            return authenticate(pub, priv);
        }

        public Uni<UserInfo> authenticate(String pub, String priv) {
            return Uni.createFrom().item(PUB.equals(pub) && PRIV.equals(priv)
                    ? new UserInfo(1, pub, 100000, "t", "t", "t") : null);
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

    private final UserService.Cache cache = new UserService.Cache() {
        public Uni<CachedSession> get(String k) {
            return Uni.createFrom().item(store.get(k));
        }

        public Uni<Void> evict(String k) {
            store.remove(k);
            return Uni.createFrom().voidItem();
        }

        public Multi<CachedSession> scan() {
            return Multi.createFrom().iterable(List.copyOf(store.values()));
        }

        public Uni<Void> put(String k, CachedSession s) {
            store.put(k, s);
            return Uni.createFrom().voidItem();
        }
    };

    /** Single-threaded tests need no mutual exclusion. */
    private final UserService.Lock noLock = new UserService.Lock() {
        public <T> Uni<T> withLock(String publicKey, Supplier<Uni<T>> action) {
            return Uni.createFrom().deferred(action::get);
        }
    };

    private final ExpeditionEndpoints endpoints = new ExpeditionEndpoints(new UserService(repo, cache, noLock, 10000));

    private JsonNode start(String priv, String cost) throws Exception {
        JsonNode params = mapper.readTree("{\"user\":\"" + PUB + "\",\"private_key\":\"" + priv + "\""
                + (cost == null ? "" : ",\"cost\":" + cost) + "}");
        return mapper.valueToTree(endpoints.startExpedition().handle(params).await().indefinitely());
    }

    @Test
    void startDebitsTheCachedSession() throws Exception {
        JsonNode r = start(PRIV, "1500");
        assertEquals(200, r.get("status").asInt());
        assertEquals(98500, r.at("/payload/money").asInt());
        assertEquals(98500, store.get(PUB).user().money());
        assertTrue(store.get(PUB).dirty(), "the debit must be written by the next synchronization");
    }

    @Test
    void startRefusesWhenTooPoor() throws Exception {
        JsonNode r = start(PRIV, "100001");
        assertEquals(402, r.get("status").asInt());
        assertEquals(100000, r.at("/payload/money").asInt());
        assertEquals(100000, store.get(PUB).user().money());
        assertFalse(store.get(PUB).dirty());
    }

    @Test
    void startChecksKeyAndCost() throws Exception {
        assertEquals(401, start("bad", "10").get("status").asInt());
        assertEquals(400, start(PRIV, null).get("status").asInt());
        assertEquals(400, start(PRIV, "0").get("status").asInt());
        assertEquals(400, start(PRIV, "\"10\"").get("status").asInt());
    }

    @RepeatedTest(20)
    void nameIsANounAndAnAdjective() {
        String name = ExpeditionCatalog.name(new Random());
        assertTrue(name.matches("\\S+ \\S+"), name);
    }
}
