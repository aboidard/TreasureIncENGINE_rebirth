package org.bogomips.treasureInc.engine.messaging;

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

/** Answers "healthcheck" requests with the result of the liveness check (also served on /q/health/live). */
@ApplicationScoped
public class HealthEndpoint implements RequestHandler {

    @Liveness
    public static class Alive implements HealthCheck {

        @Override
        public HealthCheckResponse call() {
            return HealthCheckResponse.up("alive");
        }
    }

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

    private static Map<String, Object> toMap(HealthCheckResponse response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", response.getName());
        result.put("status", response.getStatus().name());
        result.put("data", response.getData().map(d -> (Object) d).orElse(Map.of()));
        return result;
    }
}
