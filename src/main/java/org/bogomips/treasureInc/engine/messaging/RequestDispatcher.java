package org.bogomips.treasureInc.engine.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Transport-agnostic core: parses a request envelope, routes it to the {@link RequestHandler}
 * registered for its type and builds the reply envelope. New request types only need a new handler bean.
 */
@ApplicationScoped
public class RequestDispatcher {

    private static final Logger LOG = Logger.getLogger(RequestDispatcher.class);
    private static final Pattern SENSITIVE = Pattern.compile("(?i).*(private_key|password|secret|token).*");
    private static final int MAX_LOGGED_JSON_CHARS = 2000;

    /** The envelope to publish back; replyId is kept apart for logging. */
    public record Reply(String replyId, String json) {
    }

    private final ObjectMapper mapper;
    private final Map<String, RequestHandler> handlers = new HashMap<>();

    public RequestDispatcher(ObjectMapper mapper, @Any Instance<RequestHandler> handlerBeans) {
        this.mapper = mapper;
        handlerBeans.forEach(h -> handlers.put(h.type(), h));
    }

    /** Emits null (nothing to reply) when the message is malformed or has no replyId. */
    public Uni<Reply> dispatch(String message) {
        long start = System.nanoTime();
        JsonNode root;
        try {
            root = mapper.readTree(message);
        } catch (JsonProcessingException e) {
            LOG.warnf("event=message_rejected reason=malformed_json bytes=%d error=\"%s\"", message.length(),
                    e.getOriginalMessage());
            return Uni.createFrom().nullItem();
        }
        JsonNode replyId = root.get("replyId");
        if (replyId == null || replyId.isNull()) {
            LOG.warnf("event=message_rejected reason=missing_replyId bytes=%d keys=%s", message.length(),
                    root.properties().stream().map(Map.Entry::getKey).toList());
            return Uni.createFrom().nullItem();
        }
        String id = replyId.asText();
        JsonNode payload = root.path("payload");
        String type = payload.path("type").asText(null);
        LOG.debugf("event=request_received replyId=%s type=%s bytes=%d params=%s", id, type, message.length(),
                loggable(payload.path("params")));

        RequestHandler handler = handlers.get(type);
        Uni<Object> result;
        if (handler == null) {
            LOG.warnf("event=request_unhandled replyId=%s type=%s knownTypes=%s", id, type, knownTypes());
            result = Uni.createFrom().item(error("unknown request type: " + type));
        } else {
            result = handler.handle(payload.path("params"))
                    .onFailure().recoverWithItem(t -> {
                        LOG.errorf(t, "event=handler_failed replyId=%s type=%s reason=%s", id, type, t.toString());
                        return error(t.getMessage());
                    });
        }
        return result.map(r -> {
            JsonNode body = mapper.valueToTree(r);
            JsonNode status = body.get("status");
            LOG.debugf("event=request_handled replyId=%s type=%s status=%s durationMs=%.2f reply=%s",
                    id, type, status == null ? "n/a" : status.asText(), (System.nanoTime() - start) / 1_000_000.0,
                    loggable(body));
            return new Reply(id, envelope(replyId, body));
        });
    }

    Set<String> knownTypes() {
        return Set.copyOf(handlers.keySet());
    }

    private static Map<String, Object> error(String message) {
        return Map.of("error", String.valueOf(message));
    }

    private String envelope(JsonNode replyId, JsonNode payload) {
        ObjectNode out = mapper.createObjectNode();
        out.set("replyId", replyId);
        out.set("payload", payload);
        return out.toString();
    }

    // Log lines are "event=... key=value ..." (human readable, parsable by Kibana kv/grok).

    /** JSON for a log line: sensitive fields masked, truncated past {@link #MAX_LOGGED_JSON_CHARS}. */
    private String loggable(JsonNode node) {
        String s = String.valueOf(mask(node));
        return s.length() > MAX_LOGGED_JSON_CHARS ? s.substring(0, MAX_LOGGED_JSON_CHARS) + "...(truncated)" : s;
    }

    private JsonNode mask(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode copy = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                copy.set(e.getKey(), SENSITIVE.matcher(e.getKey()).matches()
                        ? mapper.getNodeFactory().textNode("***")
                        : mask(e.getValue()));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = mapper.createArrayNode();
            node.forEach(n -> copy.add(mask(n)));
            return copy;
        }
        return node;
    }
}
