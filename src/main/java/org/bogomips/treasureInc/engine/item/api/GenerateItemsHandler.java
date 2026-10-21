package org.bogomips.treasureInc.engine.item.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.item.application.ItemService;
import org.bogomips.treasureInc.engine.item.domain.ItemInfo;
import org.bogomips.treasureInc.engine.messaging.Params;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;
import org.bogomips.treasureInc.engine.messaging.Responses;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class GenerateItemsHandler implements RequestHandler {

    static final int MAX_ITEMS = 100;

    private final ItemService items;

    public GenerateItemsHandler(ItemService items) {
        this.items = items;
    }

    @Override
    public String type() {
        return "generateItemsForUser";
    }

    @Override
    public Uni<Object> handle(JsonNode params) {
        String publicKey = Params.publicKey(params);
        String privateKey = Params.text(params, "private_key");
        Integer nb = Params.integer(params, "nb", 1);
        if (publicKey == null || privateKey == null || nb == null || nb < 1 || nb > MAX_ITEMS) {
            return Uni.createFrom().item(Responses.badRequest());
        }
        return items.generate(publicKey, privateKey, nb).map(generated -> {
            if (generated == null) {
                return Responses.unauthorized();
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("items", generated.stream().map(ItemInfo::toMap).toList());
            return Responses.of(200, "OK", payload);
        });
    }
}
