package org.bogomips.treasureInc.engine.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/** Helpers to build log lines as "event=... key=value ..." (human readable, parsable by Kibana kv/grok). */
final class MessageLog {

    private static final Pattern SENSITIVE = Pattern.compile("(?i).*(private_key|password|secret|token).*");
    private static final int MAX_JSON_CHARS = 2000;

    private MessageLog() {
    }

    static JsonNode mask(ObjectMapper mapper, JsonNode node) {
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
                        : mask(mapper, e.getValue()));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = mapper.createArrayNode();
            node.forEach(n -> copy.add(mask(mapper, n)));
            return copy;
        }
        return node;
    }

    static String json(ObjectMapper mapper, JsonNode node) {
        String s = String.valueOf(mask(mapper, node));
        return s.length() > MAX_JSON_CHARS ? s.substring(0, MAX_JSON_CHARS) + "...(truncated)" : s;
    }
}
