package org.bogomips.treasureInc.engine.user;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;
import org.bogomips.treasureInc.engine.messaging.Responses;

import java.util.Map;

@ApplicationScoped
public class CreateUserHandler implements RequestHandler {

    private final UserService users;

    public CreateUserHandler(UserService users) {
        this.users = users;
    }

    @Override
    public String type() {
        return "createUser";
    }

    @Override
    public Uni<Object> handle(JsonNode params) {
        // The private key is returned once, at creation: it is not recoverable afterwards.
        return users.create()
                .map(created -> Responses.of(201, "Created", Map.of("user", created.user().toMap(created.privateKey()))))
                .onFailure(KeyGenerationException.class)
                .recoverWithItem(e -> Responses.of(500, "Could not generate a unique key"));
    }
}
