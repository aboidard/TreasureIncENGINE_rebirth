package org.bogomips.treasureInc.engine.item.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.item.application.ItemService;
import org.bogomips.treasureInc.engine.messaging.Params;
import org.bogomips.treasureInc.engine.user.application.UserService;

/** Same listing, but the private key must match. */
@ApplicationScoped
public class GetPrivateItemsHandler extends AbstractGetItemsHandler {

    private UserService users;

    GetPrivateItemsHandler() {
    }

    public GetPrivateItemsHandler(ItemService items, UserService users) {
        super(items);
        this.users = users;
    }

    @Override
    public String type() {
        return "getPrivateItems";
    }

    @Override
    Uni<Boolean> authorize(String publicKey, JsonNode params) {
        String privateKey = Params.text(params, "private_key");
        if (privateKey == null) {
            return Uni.createFrom().item(false);
        }
        return users.authenticate(publicKey, privateKey).map(u -> u != null);
    }
}
