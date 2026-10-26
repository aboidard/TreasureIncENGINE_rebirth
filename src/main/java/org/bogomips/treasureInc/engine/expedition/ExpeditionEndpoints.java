package org.bogomips.treasureInc.engine.expedition;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.bogomips.treasureInc.engine.messaging.Protocol;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;
import org.bogomips.treasureInc.engine.user.UserService;

import java.util.Map;

/** Expedition request types: validates params, calls the services and shapes the reply. */
@ApplicationScoped
class ExpeditionEndpoints {

    private final UserService users;

    ExpeditionEndpoints(UserService users) {
        this.users = users;
    }

    /** Pays for an expedition: the cost is taken from the balance, or refused (402) if the balance is too low. */
    @Produces
    RequestHandler startExpedition() {
        return RequestHandler.of("startExpedition", this::start);
    }

    private Uni<Object> start(JsonNode params) {
        String publicKey = Protocol.publicKey(params);
        String privateKey = Protocol.text(params, "private_key");
        // no default: a missing cost is a bad request, not a free expedition
        Integer cost = params == null || params.get("cost") == null ? null : Protocol.integer(params, "cost", 0);
        if (publicKey == null || privateKey == null || cost == null || cost < 1) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return users.spend(publicKey, privateKey, cost).map(result -> switch (result.status()) {
            case UNAUTHORIZED -> Protocol.unauthorized();
            case NOT_ENOUGH_MONEY -> Protocol.reply(402, "Not enough money", Map.of("money", result.money()));
            case OK -> Protocol.reply(200, "OK", Map.of("money", result.money()));
        });
    }
}
