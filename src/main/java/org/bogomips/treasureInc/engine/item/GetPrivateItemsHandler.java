package org.bogomips.treasureInc.engine.item;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.messaging.Protocol;
import org.bogomips.treasureInc.engine.user.UserService;

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
        String privateKey = Protocol.text(params, "private_key");
        if (privateKey == null) {
            return Uni.createFrom().item(false);
        }
        return users.authenticate(publicKey, privateKey).map(u -> u != null);
    }
}
