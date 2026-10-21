package org.bogomips.treasureInc.engine.user.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

public record UserInfo(
        @JsonProperty("id") int id,
        @JsonProperty("public_key") String publicKey,
        @JsonProperty("money") int money,
        @JsonProperty("lastLogin") String lastLogin,
        @JsonProperty("createdat") String createdAt,
        @JsonProperty("updatedat") String updatedAt) {

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
        m.put("lastLogin", lastLogin);
        m.put("createdat", createdAt);
        m.put("updatedat", updatedAt);
        return m;
    }
}
