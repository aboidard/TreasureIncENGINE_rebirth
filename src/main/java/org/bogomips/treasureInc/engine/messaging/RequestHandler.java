package org.bogomips.treasureInc.engine.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;

public interface RequestHandler {
    String type();

    Uni<Object> handle(JsonNode params);
}
