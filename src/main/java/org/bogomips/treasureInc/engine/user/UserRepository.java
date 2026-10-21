package org.bogomips.treasureInc.engine.user;

import io.smallrye.mutiny.Uni;

public interface UserRepository {
    /** Updates last login and returns the user if the credentials match, null otherwise. */
    Uni<UserInfo> login(String publicKey, String privateKey);

    /** Checks the credentials without any update; null if they do not match. */
    Uni<UserInfo> authenticate(String publicKey, String privateKey);

    Uni<Boolean> exists(String publicKey);

    Uni<UserInfo> create(String publicKey, String privateKey, int money);

    /** Writes the balance held in Redis. */
    Uni<Void> updateMoney(int userId, int money);
}
