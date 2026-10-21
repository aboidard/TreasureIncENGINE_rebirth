package org.bogomips.treasureInc.engine.user.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.messaging.Params;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;
import org.bogomips.treasureInc.engine.messaging.Responses;
import org.bogomips.treasureInc.engine.user.application.UserService;

import java.util.Map;

@ApplicationScoped
public class LoginHandler implements RequestHandler {

    private final UserService users;

    public LoginHandler(UserService users) {
        this.users = users;
    }

    @Override
    public String type() {
        return "login";
    }

    @Override
    public Uni<Object> handle(JsonNode params) {
        String publicKey = Params.text(params, "user");
        String privateKey = Params.text(params, "private_key");
        if (publicKey == null || privateKey == null) {
            return Uni.createFrom().item(Responses.badRequest());
        }
        return users.login(publicKey, privateKey).map(user -> user == null
                ? Responses.unauthorized()
                : Responses.of(200, "OK", Map.of("user", user.toPublicMap())));
    }
}
