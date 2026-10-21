package org.bogomips.treasureInc.engine.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LoginHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger dbCalls = new AtomicInteger();
    private final Map<String, CachedSession> store = new HashMap<>();

    private final UserRepository repo = new UserRepository() {
        public Uni<UserInfo> login(String pub, String priv) {
            dbCalls.incrementAndGet();
            return Uni.createFrom().item("12345678900".equals(pub) && "secret".equals(priv)
                    ? new UserInfo(1, pub, 100000, "2026-10-15T16:26:42.623Z", "2026-10-14T20:02:43.529Z",
                            "2026-10-15T16:26:42.624Z")
                    : null);
        }

        public Uni<UserInfo> authenticate(String pub, String priv) {
            return Uni.createFrom().nullItem();
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

    private final UserCache cache = new UserCache() {
        public Uni<CachedSession> get(String k) {
            return Uni.createFrom().item(store.get(k));
        }

        public Uni<Void> evict(String k) {
            store.remove(k);
            return Uni.createFrom().voidItem();
        }

        public io.smallrye.mutiny.Multi<CachedSession> scan() {
            return io.smallrye.mutiny.Multi.createFrom().iterable(java.util.List.copyOf(store.values()));
        }

        public Uni<Void> put(String k, CachedSession s) {
            store.put(k, s);
            return Uni.createFrom().voidItem();
        }
    };

    private JsonNode login(String pub, String priv) throws Exception {
        JsonNode params = mapper.readTree("{\"user\":\"" + pub + "\",\"private_key\":\"" + priv + "\"}");
        Object r = new LoginHandler(new UserService(repo, cache, new org.bogomips.treasureInc.engine.testsupport.NoopUserLock(), 10000)).handle(params).await().indefinitely();
        return mapper.valueToTree(r);
    }

    @Test
    void loginFromDbThenCache() throws Exception {
        JsonNode first = login("12345678900", "secret");
        assertEquals(200, first.get("status").asInt());
        assertEquals("", first.at("/payload/user/private_key").asText());
        assertEquals(100000, first.at("/payload/user/money").asInt());
        assertTrue(store.containsKey("12345678900"));
        assertNotNull(store.get("12345678900").lastAction());

        JsonNode second = login("12345678900", "secret");
        assertEquals(200, second.get("status").asInt());
        assertEquals(1, dbCalls.get());
    }

    @Test
    void wrongKeyIsUnauthorized() throws Exception {
        assertEquals(401, login("12345678900", "bad").get("status").asInt());
        login("12345678900", "secret");
        assertEquals(401, login("12345678900", "bad").get("status").asInt());
    }
}
