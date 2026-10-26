package org.bogomips.treasureInc.engine.user;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.bogomips.treasureInc.engine.messaging.Protocol;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;
import org.bogomips.treasureInc.engine.user.UserService.KeyGenerationException;

import java.util.Map;

/** User request types: validates params, calls {@link UserService} and shapes the reply. */
@ApplicationScoped
class UserEndpoints {

    private final UserService users;

    UserEndpoints(UserService users) {
        this.users = users;
    }

    @Produces
    RequestHandler createUser() {
        return RequestHandler.of("createUser", this::create);
    }

    @Produces
    RequestHandler login() {
        return RequestHandler.of("login", this::login);
    }

    /** Pays for an expedition: the cost is taken from the balance, or refused (402) if the balance is too low. */
    @Produces
    RequestHandler startExpedition() {
        return RequestHandler.of("startExpedition", this::startExpedition);
    }

    private Uni<Object> create(JsonNode params) {
        // The private key is returned once, at creation: it is not recoverable afterwards.
        return users.create()
                .map(created -> Protocol.reply(201, "Created", Map.of("user", created.user().toMap(created.privateKey()))))
                .onFailure(KeyGenerationException.class)
                .recoverWithItem(e -> Protocol.reply(500, "Could not generate a unique key"));
    }

    private Uni<Object> login(JsonNode params) {
        String publicKey = Protocol.text(params, "user");
        String privateKey = Protocol.text(params, "private_key");
        if (publicKey == null || privateKey == null) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return users.login(publicKey, privateKey).map(user -> user == null
                ? Protocol.unauthorized()
                : Protocol.reply(200, "OK", Map.of("user", user.toPublicMap())));
    }

    private Uni<Object> startExpedition(JsonNode params) {
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
