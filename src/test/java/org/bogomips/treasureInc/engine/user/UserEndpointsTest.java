package org.bogomips.treasureInc.engine.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.bogomips.treasureInc.engine.user.UserService.CachedSession;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class UserEndpointsTest {

    private static final String PUB = "12345678900";
    private static final String PRIV = "secret";

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger dbLogins = new AtomicInteger();
    private final Map<String, CachedSession> store = new HashMap<>();
    private final Set<String> created = new HashSet<>();

    private final UserService.Repository repo = new UserService.Repository() {
        public Uni<UserInfo> login(String pub, String priv) {
            dbLogins.incrementAndGet();
            return Uni.createFrom().item(PUB.equals(pub) && PRIV.equals(priv)
                    ? new UserInfo(1, pub, 100000, "2026-10-15T16:26:42.623Z", "2026-10-14T20:02:43.529Z",
                            "2026-10-15T16:26:42.624Z")
                    : null);
        }

        public Uni<UserInfo> authenticate(String pub, String priv) {
            return Uni.createFrom().nullItem();
        }

        public Uni<Boolean> exists(String pub) {
            return Uni.createFrom().item(created.contains(pub));
        }

        public Uni<Void> updateMoney(int id, int money) {
            return Uni.createFrom().voidItem();
        }

        public Uni<UserInfo> create(String pub, String priv, int money) {
            created.add(pub);
            return Uni.createFrom().item(new UserInfo(1, pub, money, "t", "t", "t"));
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

    private final UserEndpoints endpoints = new UserEndpoints(new UserService(repo, cache, noLock, 10000));

    private JsonNode login(String pub, String priv) throws Exception {
        JsonNode params = mapper.readTree("{\"user\":\"" + pub + "\",\"private_key\":\"" + priv + "\"}");
        return mapper.valueToTree(endpoints.login().handle(params).await().indefinitely());
    }

    @Test
    void createsAndCachesUser() {
        JsonNode n = mapper.valueToTree(endpoints.createUser().handle(null).await().indefinitely());
        assertEquals(201, n.get("status").asInt());
        String pub = n.at("/payload/user/public_key").asText();
        String priv = n.at("/payload/user/private_key").asText();
        assertTrue(pub.matches("\\d{11}"));
        assertTrue(priv.matches("[A-Za-z0-9]{30}"));
        assertEquals(10000, n.at("/payload/user/money").asInt());
        assertEquals(CachedSession.hashKey(priv), store.get(pub).keyHash());
        assertTrue(created.contains(pub));
    }

    @Test
    void loginFromDbThenCache() throws Exception {
        JsonNode first = login(PUB, PRIV);
        assertEquals(200, first.get("status").asInt());
        assertEquals("", first.at("/payload/user/private_key").asText());
        assertEquals(100000, first.at("/payload/user/money").asInt());
        assertTrue(store.containsKey(PUB));
        assertNotNull(store.get(PUB).lastAction());

        JsonNode second = login(PUB, PRIV);
        assertEquals(200, second.get("status").asInt());
        assertEquals(1, dbLogins.get());
    }

    @Test
    void wrongKeyIsUnauthorized() throws Exception {
        assertEquals(401, login(PUB, "bad").get("status").asInt());
        login(PUB, PRIV);
        assertEquals(401, login(PUB, "bad").get("status").asInt());
    }
}
