package org.bogomips.treasureInc.engine.health;

import org.bogomips.treasureInc.engine.messaging.RequestHandler;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class HealthCheckHandler implements RequestHandler {

    @Inject
    @Liveness
    HealthCheck check;

    @Override
    public String type() {
        return "healthcheck";
    }

    @Override
    public Uni<Object> handle(JsonNode params) {
        return Uni.createFrom().item(() -> (Object) toMap(check.call()))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    static Map<String, Object> toMap(HealthCheckResponse response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", response.getName());
        result.put("status", response.getStatus().name());
        result.put("data", response.getData().map(d -> (Object) d).orElse(Map.of()));
        return result;
    }
}
