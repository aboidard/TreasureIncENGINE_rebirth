package org.bogomips.treasureInc.engine.item;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/** Public listing: only the public key is needed (it is already public, like the leaderboard). */
@ApplicationScoped
public class GetItemsHandler extends AbstractGetItemsHandler {

    GetItemsHandler() {
    }

    public GetItemsHandler(ItemService items) {
        super(items);
    }

    @Override
    public String type() {
        return "getItems";
    }

    @Override
    Uni<Boolean> authorize(String publicKey, JsonNode params) {
        return Uni.createFrom().item(true);
    }
}
