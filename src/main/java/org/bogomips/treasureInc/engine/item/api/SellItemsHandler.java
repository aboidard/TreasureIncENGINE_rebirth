package org.bogomips.treasureInc.engine.item.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.item.application.ItemService;
import org.bogomips.treasureInc.engine.messaging.Params;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;
import org.bogomips.treasureInc.engine.messaging.Responses;

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
        String publicKey = Params.publicKey(params);
        String privateKey = Params.text(params, "private_key");
        List<Integer> ids = ids(params == null ? null : params.get("items"));
        if (publicKey == null || privateKey == null || ids == null) {
            return Uni.createFrom().item(Responses.badRequest());
        }
        return items.sell(publicKey, privateKey, ids).map(result -> switch (result.status()) {
            case UNAUTHORIZED -> Responses.unauthorized();
            case NOT_FOUND -> Responses.of(404, "Items not found");
            case OK -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("totalPrice", result.totalPrice());
                payload.put("money", result.money());
                yield Responses.of(200, "OK", payload);
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
