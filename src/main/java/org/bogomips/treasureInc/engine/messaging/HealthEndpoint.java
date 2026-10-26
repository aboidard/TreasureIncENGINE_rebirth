package org.bogomips.treasureInc.engine.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.health.SmallRyeHealth;
import io.smallrye.health.SmallRyeHealthReporter;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers "healthcheck" requests with the result of the liveness check (also served on /q/health/live), plus the
 * name and status of every readiness check (database, Redis: also served on /q/health/ready) under "checks", so
 * the API can report the database status without connecting to it.
 */
@ApplicationScoped
class HealthEndpoint implements RequestHandler {

    private static final Logger LOG = Logger.getLogger(HealthEndpoint.class);

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

    @Inject
    SmallRyeHealthReporter reporter;

    @Override
    public String type() {
        return "healthcheck";
    }

    @Override
    public Uni<Object> handle(JsonNode params) {
        Uni<Map<String, Object>> liveness = Uni.createFrom().item(() -> toMap(check.call()))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
        Uni<List<Map<String, String>>> readiness = reporter.getReadinessAsync()
                .map(HealthEndpoint::checks)
                .onFailure().recoverWithItem(t -> {
                    LOG.warnf("event=readiness_failed reason=%s", t.toString());
                    return List.of();
                });
        return Uni.combine().all().unis(liveness, readiness).asTuple().map(both -> {
            Map<String, Object> result = both.getItem1();
            result.put("checks", both.getItem2());
            return result;
        });
    }

    private static Map<String, Object> toMap(HealthCheckResponse response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", response.getName());
        result.put("status", response.getStatus().name());
        result.put("data", response.getData().map(d -> (Object) d).orElse(Map.of()));
        return result;
    }

    private static List<Map<String, String>> checks(SmallRyeHealth health) {
        return health.getPayload().getJsonArray("checks").getValuesAs(JsonObject.class).stream()
                .map(c -> Map.of("name", c.getString("name", ""), "status", c.getString("status", "DOWN")))
                .toList();
    }
}
