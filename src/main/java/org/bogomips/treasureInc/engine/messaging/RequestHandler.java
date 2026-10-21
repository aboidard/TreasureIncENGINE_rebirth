package org.bogomips.treasureInc.engine.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;

import java.util.function.Function;

/**
 * Handles one request type. Every {@code RequestHandler} bean is picked up by the {@link RequestDispatcher}:
 * either a class implementing it, or a {@code @Produces} method returning {@link #of}.
 */
public interface RequestHandler {
    String type();

    Uni<Object> handle(JsonNode params);

    static RequestHandler of(String type, Function<JsonNode, Uni<Object>> handle) {
        return new Of(type, handle);
    }

    record Of(String type, Function<JsonNode, Uni<Object>> function) implements RequestHandler {
        @Override
        public Uni<Object> handle(JsonNode params) {
            return function.apply(params);
        }
    }
}
