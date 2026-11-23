package org.bogomips.treasureInc.engine.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.bogomips.treasureInc.engine.settings.Settings;
import org.bogomips.treasureInc.engine.settings.Settings.Maintenance;
import org.bogomips.treasureInc.engine.settings.SettingsService;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The dispatcher refuses the requests of the game during a maintenance, never the administration ones. */
class MaintenanceDispatchTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private Settings stored = Settings.DEFAULT;

    private final SettingsService settings = new SettingsService(new SettingsService.Repository() {
        @Override
        public Uni<Settings> load() {
            return Uni.createFrom().item(stored);
        }

        @Override
        public Uni<Settings> saveMaintenance(Maintenance m, Instant at) {
            stored = stored.withMaintenance(m, at);
            return Uni.createFrom().item(stored);
        }

        @Override
        public Uni<Settings> saveMinClientVersion(String version, Instant at) {
            stored = stored.withMinClientVersion(version, at);
            return Uni.createFrom().item(stored);
        }
    }, new SettingsService.Mirror() {
        @Override
        public Uni<Settings> read() {
            return Uni.createFrom().nullItem();
        }

        @Override
        public Uni<Void> write(Settings s) {
            return Uni.createFrom().voidItem();
        }
    }, Clock.fixed(NOW, ZoneOffset.UTC));

    private final RequestDispatcher dispatcher = new RequestDispatcher(mapper, new Handlers(List.of(
            RequestHandler.of("login", p -> Uni.createFrom().item(Protocol.reply(200, "OK"))),
            RequestHandler.admin("forceSync", p -> Uni.createFrom().item(Protocol.reply(200, "OK"))))), settings);

    private JsonNode dispatch(String type) throws Exception {
        String in = "{\"replyId\":\"r1\",\"payload\":{\"type\":\"" + type + "\",\"params\":{}}}";
        return mapper.readTree(dispatcher.dispatch(in).await().indefinitely().json()).get("payload");
    }

    @Test
    void gameRequestsAreRefusedDuringAMaintenance() throws Exception {
        assertEquals(200, dispatch("login").get("status").asInt());

        settings.setMaintenance(new Maintenance(true, "Mise à jour du serveur", null, Instant.parse("2026-11-22T11:00:00Z")))
                .await().indefinitely();
        JsonNode refused = dispatch("login");
        assertEquals(503, refused.get("status").asInt());
        assertEquals("Mise à jour du serveur", refused.get("message").asText());
        assertEquals(3600, refused.at("/payload/retryAfter").asLong());
        assertEquals("2026-11-22T11:00:00Z", refused.at("/payload/maintenance/end").asText());
        assertEquals(200, dispatch("forceSync").get("status").asInt());

        settings.setMaintenance(Maintenance.OFF).await().indefinitely();
        assertEquals(200, dispatch("login").get("status").asInt());
    }

    @Test
    void aPlannedMaintenanceRefusesNothingYet() throws Exception {
        settings.setMaintenance(new Maintenance(true, "Ce soir", Instant.parse("2026-11-22T20:00:00Z"), null))
                .await().indefinitely();
        assertEquals(200, dispatch("login").get("status").asInt());
    }

    /** The handler beans. */
    private record Handlers(List<RequestHandler> handlers) implements Instance<RequestHandler> {
        @Override
        public Iterator<RequestHandler> iterator() {
            return handlers.iterator();
        }

        @Override
        public Instance<RequestHandler> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends RequestHandler> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends RequestHandler> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return handlers.isEmpty();
        }

        @Override
        public boolean isAmbiguous() {
            return handlers.size() > 1;
        }

        @Override
        public void destroy(RequestHandler instance) {
        }

        @Override
        public Handle<RequestHandler> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<RequestHandler>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RequestHandler get() {
            throw new UnsupportedOperationException();
        }
    }
}
