package org.bogomips.treasureInc.engine.settings;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.bogomips.treasureInc.engine.messaging.Protocol;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;
import org.bogomips.treasureInc.engine.settings.Settings.Maintenance;

/**
 * Administration request types of the settings, sent by Maestro straight on the Redis request channel (the API only
 * relays the request types of the game). They are served during a maintenance. Each one answers the settings:
 * {"maintenance": {"enabled", "active", "reason", "start", "end"}, "minClientVersion", "updatedAt"}.
 */
@ApplicationScoped
class SettingsEndpoints {

    private final SettingsService settings;

    SettingsEndpoints(SettingsService settings) {
        this.settings = settings;
    }

    /** The settings, read again from Redis (or the database). */
    @Produces
    RequestHandler getSettings() {
        return RequestHandler.admin("getSettings", params -> settings.refresh().map(this::ok));
    }

    /**
     * params: {"enabled", "reason", "start", "end"}: switches the maintenance on or off; the reason is required to
     * switch it on, start and end (ISO-8601) are optional. 400 with what is wrong.
     */
    @Produces
    RequestHandler setMaintenance() {
        return RequestHandler.admin("setMaintenance", params -> {
            Maintenance m;
            try {
                m = Maintenance.parse(params);
            } catch (IllegalArgumentException e) {
                return invalid(e.getMessage());
            }
            return settings.setMaintenance(m).map(this::ok);
        });
    }

    /** params: {"version"}: the oldest version of the Unity client that may log in ("0": any). */
    @Produces
    RequestHandler setMinClientVersion() {
        return RequestHandler.admin("setMinClientVersion", params -> {
            String version = Protocol.text(params, "version");
            if (version == null || !Settings.VERSION.matcher(version.strip()).matches()) {
                return invalid("version is numbers separated by dots, e.g. 0.22 or 1.4.2");
            }
            return settings.setMinClientVersion(version.strip()).map(this::ok);
        });
    }

    private Object ok(Settings s) {
        return Protocol.reply(200, "OK", s.toMap(settings.now()));
    }

    private static Uni<Object> invalid(String message) {
        return Uni.createFrom().item(Protocol.reply(400, "Bad Request: " + message));
    }
}
