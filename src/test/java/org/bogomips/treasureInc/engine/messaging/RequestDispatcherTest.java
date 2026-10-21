package org.bogomips.treasureInc.engine.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class RequestDispatcherTest {

    @Inject
    RequestDispatcher dispatcher;

    @Inject
    ObjectMapper mapper;

    @Test
    void healthcheckReplyKeepsReplyId() throws Exception {
        String in = "{\"replyId\":\"abc\",\"payload\":{\"type\":\"healthcheck\",\"params\":{}}}";
        JsonNode out = mapper.readTree(dispatcher.dispatch(in).await().indefinitely().json());
        assertEquals("abc", out.get("replyId").asText());
        assertEquals("UP", out.at("/payload/status").asText());
        assertEquals("alive", out.at("/payload/name").asText());
    }

    @Test
    void everyRequestTypeIsRegistered() {
        assertEquals(Set.of("healthcheck", "getItems", "getPrivateItems", "generateItemsForUser", "sellItems",
                "createUser", "login"), dispatcher.knownTypes());
    }

    @Test
    void unknownTypeReturnsError() throws Exception {
        String in = "{\"replyId\":\"x\",\"payload\":{\"type\":\"nope\",\"params\":{}}}";
        JsonNode out = mapper.readTree(dispatcher.dispatch(in).await().indefinitely().json());
        assertEquals("x", out.get("replyId").asText());
        assertEquals(true, out.at("/payload/error").isTextual());
    }
}
