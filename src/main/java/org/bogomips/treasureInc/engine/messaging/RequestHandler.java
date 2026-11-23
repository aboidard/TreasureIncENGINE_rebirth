package org.bogomips.treasureInc.engine.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;

import java.util.function.Function;

/**
 * Handles one request type. Every {@code RequestHandler} bean is picked up by the {@link RequestDispatcher}:
 * either a class implementing it, or a {@code @Produces} method returning {@link #of} (a request of the game) or
 * {@link #admin} (an administration request, sent by Maestro).
 */
public interface RequestHandler {
    String type();

    Uni<Object> handle(JsonNode params);

    /**
     * True for the administration requests (sent by Maestro, never relayed by the API) and the healthcheck: they are
     * still served during a maintenance, when the requests of the game are refused.
     */
    default boolean admin() {
        return false;
    }

    static RequestHandler of(String type, Function<JsonNode, Uni<Object>> handle) {
        return new Of(type, handle, false);
    }

    /** An administration request: served even during a maintenance. */
    static RequestHandler admin(String type, Function<JsonNode, Uni<Object>> handle) {
        return new Of(type, handle, true);
    }

    record Of(String type, Function<JsonNode, Uni<Object>> function, boolean admin) implements RequestHandler {
        @Override
        public Uni<Object> handle(JsonNode params) {
            return function.apply(params);
        }
    }
}
