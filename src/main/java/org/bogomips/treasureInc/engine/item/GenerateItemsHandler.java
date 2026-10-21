package org.bogomips.treasureInc.engine.item;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.messaging.Protocol;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;

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
        String publicKey = Protocol.publicKey(params);
        String privateKey = Protocol.text(params, "private_key");
        Integer nb = Protocol.integer(params, "nb", 1);
        if (publicKey == null || privateKey == null || nb == null || nb < 1 || nb > MAX_ITEMS) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return items.generate(publicKey, privateKey, nb).map(generated -> {
            if (generated == null) {
                return Protocol.unauthorized();
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("items", generated.stream().map(ItemInfo::toMap).toList());
            return Protocol.reply(200, "OK", payload);
        });
    }
}
