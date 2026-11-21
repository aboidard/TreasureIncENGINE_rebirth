package org.bogomips.treasureInc.engine.user;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A user as the engine works on it. money is the gold of the game, gems the premium currency (summons...).
 * Sessions cached before the gems existed have no "gems": they are read as 0.
 */
public record UserInfo(
        @JsonProperty("id") int id,
        @JsonProperty("public_key") String publicKey,
        @JsonProperty("money") int money,
        @JsonProperty("gems") int gems,
        @JsonProperty("lastLogin") String lastLogin,
        @JsonProperty("createdat") String createdAt,
        @JsonProperty("updatedat") String updatedAt) {

    /** A user without gems. */
    public UserInfo(int id, String publicKey, int money, String lastLogin, String createdAt, String updatedAt) {
        this(id, publicKey, money, 0, lastLogin, createdAt, updatedAt);
    }

    /** A copy with other balances. */
    public UserInfo withBalance(int money, int gems) {
        return new UserInfo(id, publicKey, money, gems, lastLogin, createdAt, updatedAt);
    }

    // The private key is never exposed: always an empty string.
    public Map<String, Object> toPublicMap() {
        return toMap("");
    }

    public Map<String, Object> toMap(String privateKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("public_key", publicKey);
        m.put("private_key", privateKey);
        m.put("money", money);
        m.put("gems", gems);
        m.put("lastLogin", lastLogin);
        m.put("createdat", createdAt);
        m.put("updatedat", updatedAt);
        return m;
    }
}
