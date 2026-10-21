package org.bogomips.treasureInc.engine.item;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.messaging.Protocol;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class SellItemsHandler implements RequestHandler {

    static final int MAX_ITEMS = 1000;

    private final ItemService items;

    public SellItemsHandler(ItemService items) {
        this.items = items;
    }

    @Override
    public String type() {
        return "sellItems";
    }

    @Override
    public Uni<Object> handle(JsonNode params) {
        String publicKey = Protocol.publicKey(params);
        String privateKey = Protocol.text(params, "private_key");
        List<Integer> ids = ids(params == null ? null : params.get("items"));
        if (publicKey == null || privateKey == null || ids == null) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return items.sell(publicKey, privateKey, ids).map(result -> switch (result.status()) {
            case UNAUTHORIZED -> Protocol.unauthorized();
            case NOT_FOUND -> Protocol.reply(404, "Items not found");
            case OK -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("totalPrice", result.totalPrice());
                payload.put("money", result.money());
                yield Protocol.reply(200, "OK", payload);
            }
        });
    }

    /** Distinct item ids, or null if the list is missing, empty, too long or malformed. */
    private static List<Integer> ids(JsonNode list) {
        if (list == null || !list.isArray() || list.isEmpty() || list.size() > MAX_ITEMS) {
            return null;
        }
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (JsonNode item : list) {
            JsonNode id = item.get("id");
            if (id == null || !id.isIntegralNumber() || !id.canConvertToInt()) {
                return null;
            }
            ids.add(id.intValue());
        }
        return List.copyOf(ids);
    }
}
