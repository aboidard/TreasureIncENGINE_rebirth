package org.bogomips.treasureInc.engine.user;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.messaging.Protocol;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;

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
        String publicKey = Protocol.text(params, "user");
        String privateKey = Protocol.text(params, "private_key");
        if (publicKey == null || privateKey == null) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return users.login(publicKey, privateKey).map(user -> user == null
                ? Protocol.unauthorized()
                : Protocol.reply(200, "OK", Map.of("user", user.toPublicMap())));
    }
}
