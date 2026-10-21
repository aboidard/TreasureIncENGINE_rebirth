package org.bogomips.treasureInc.engine.messaging;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the standard {status, message, payload} reply body. */
public final class Responses {

    private Responses() {
    }

    public static Object of(int status, String message, Object payload) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", status);
        r.put("message", message);
        if (payload != null) {
            r.put("payload", payload);
        }
        return r;
    }

    public static Object of(int status, String message) {
        return of(status, message, null);
    }

    public static Object badRequest() {
        return of(400, "Bad Request");
    }

    public static Object unauthorized() {
        return of(401, "Unauthorized");
    }
}
