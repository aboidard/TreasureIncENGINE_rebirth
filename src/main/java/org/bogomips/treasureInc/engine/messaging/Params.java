package org.bogomips.treasureInc.engine.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Pattern;

/** Typed, null-safe readers for request params. */
public final class Params {

    private static final Pattern PUBLIC_KEY = Pattern.compile("\\d{11}");

    private Params() {
    }

    public static String text(JsonNode params, String field) {
        JsonNode n = params == null ? null : params.get(field);
        return n == null || !n.isTextual() || n.asText().isEmpty() ? null : n.asText();
    }

    /** Public key, read from "user" (as for login) or "public_key"; null if absent or malformed. */
    public static String publicKey(JsonNode params) {
        String key = text(params, "user");
        if (key == null) {
            key = text(params, "public_key");
        }
        return key != null && PUBLIC_KEY.matcher(key).matches() ? key : null;
    }

    /** Integer field with a default when absent; null when present but not an integer. */
    public static Integer integer(JsonNode params, String field, int defaultValue) {
        JsonNode n = params == null ? null : params.get(field);
        if (n == null || n.isNull()) {
            return defaultValue;
        }
        return n.isIntegralNumber() && n.canConvertToInt() ? n.intValue() : null;
    }
}
