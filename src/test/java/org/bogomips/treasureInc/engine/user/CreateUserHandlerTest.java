package org.bogomips.treasureInc.engine.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import org.bogomips.treasureInc.engine.user.UserService.CachedSession;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CreateUserHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedSession> store = new HashMap<>();
    private final Set<String> created = new HashSet<>();

    private final UserService.Repository repo = new UserService.Repository() {
        public Uni<UserInfo> login(String pub, String priv) {
            return Uni.createFrom().nullItem();
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

        public io.smallrye.mutiny.Multi<CachedSession> scan() {
            return io.smallrye.mutiny.Multi.createFrom().iterable(java.util.List.copyOf(store.values()));
        }

        public Uni<Void> put(String k, CachedSession s) {
            store.put(k, s);
            return Uni.createFrom().voidItem();
        }
    };

    @Test
    void createsAndCachesUser() {
        Object r = new UserEndpoints(new UserService(repo, cache, new org.bogomips.treasureInc.engine.testsupport.NoopUserLock(), 10000)).createUser().handle(null).await().indefinitely();
        JsonNode n = mapper.valueToTree(r);
        assertEquals(201, n.get("status").asInt());
        String pub = n.at("/payload/user/public_key").asText();
        String priv = n.at("/payload/user/private_key").asText();
        assertTrue(pub.matches("\\d{11}"));
        assertTrue(priv.matches("[A-Za-z0-9]{30}"));
        assertEquals(10000, n.at("/payload/user/money").asInt());
        assertEquals(CachedSession.hashKey(priv), store.get(pub).keyHash());
        assertTrue(created.contains(pub));
    }
}
