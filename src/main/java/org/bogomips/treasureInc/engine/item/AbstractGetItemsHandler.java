package org.bogomips.treasureInc.engine.item;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import org.bogomips.treasureInc.engine.messaging.Protocol;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/** Template for item listings; subclasses only decide who is authorized. */
abstract class AbstractGetItemsHandler implements RequestHandler {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    protected ItemService items;

    // Required by CDI client proxies.
    AbstractGetItemsHandler() {
    }

    AbstractGetItemsHandler(ItemService items) {
        this.items = items;
    }

    /** Emits true when the caller is allowed to read the items. */
    abstract Uni<Boolean> authorize(String publicKey, JsonNode params);

    @Override
    public Uni<Object> handle(JsonNode params) {
        String publicKey = Protocol.publicKey(params);
        Integer page = Protocol.integer(params, "page", 1);
        Integer limit = Protocol.integer(params, "limit", DEFAULT_LIMIT);
        if (publicKey == null || page == null || limit == null || page < 1 || limit < 1 || limit > MAX_LIMIT) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return authorize(publicKey, params).flatMap(ok -> {
            if (!ok) {
                return Uni.createFrom().item(Protocol.unauthorized());
            }
            return items.page(publicKey, page, limit).map(result -> {
                if (result == null) {
                    return Protocol.reply(404, "Not Found");
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("items", result.items().stream().map(ItemInfo::toMap).toList());
                payload.put("page", result.page());
                payload.put("limit", result.limit());
                payload.put("total", result.total());
                return Protocol.reply(200, "OK", payload);
            });
        });
    }
}
