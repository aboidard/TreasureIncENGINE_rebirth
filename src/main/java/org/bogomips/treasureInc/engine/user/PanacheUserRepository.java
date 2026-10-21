package org.bogomips.treasureInc.engine.user;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@ApplicationScoped
public class PanacheUserRepository implements UserService.Repository {

    @Override
    public Uni<UserInfo> login(String publicKey, String privateKey) {
        return Panache.withTransaction(() ->
                User.<User>find("publicKey = ?1 and privateKey = ?2", publicKey, privateKey)
                        .firstResult()
                        .map(user -> {
                            if (user == null) {
                                return null;
                            }
                            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                            user.lastLogin = now;
                            user.updatedAt = now;
                            return user.toInfo();
                        }));
    }

    @Override
    public Uni<UserInfo> authenticate(String publicKey, String privateKey) {
        return Panache.withSession(() ->
                User.<User>find("publicKey = ?1 and privateKey = ?2", publicKey, privateKey)
                        .firstResult()
                        .map(user -> user == null ? null : user.toInfo()));
    }

    @Override
    public Uni<Boolean> exists(String publicKey) {
        return Panache.withSession(() -> User.count("publicKey", publicKey)).map(n -> n > 0);
    }

    @Override
    public Uni<Void> updateMoney(int userId, int money) {
        return Panache.withTransaction(() -> User.<User>findById(userId).invoke(user -> {
            if (user != null) {
                user.money = money;
                user.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
            }
        })).replaceWithVoid();
    }

    @Override
    public Uni<UserInfo> create(String publicKey, String privateKey, int money) {
        User user = new User();
        user.publicKey = publicKey;
        user.privateKey = privateKey;
        user.money = money;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        user.createdAt = now;
        user.updatedAt = now;
        user.lastLogin = now;
        return Panache.withTransaction(() -> user.<User>persist()).map(User::toInfo);
    }
}
