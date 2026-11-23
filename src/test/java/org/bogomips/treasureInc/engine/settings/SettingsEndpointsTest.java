package org.bogomips.treasureInc.engine.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;
import org.bogomips.treasureInc.engine.settings.Settings.Maintenance;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class SettingsEndpointsTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();

    /** The database row and the Redis key. */
    private Settings stored = Settings.DEFAULT;
    private Settings mirrored;

    private final SettingsService.Repository repository = new SettingsService.Repository() {
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
    };

    private final SettingsService.Mirror mirror = new SettingsService.Mirror() {
        @Override
        public Uni<Settings> read() {
            return Uni.createFrom().item(mirrored);
        }

        @Override
        public Uni<Void> write(Settings s) {
            mirrored = s;
            return Uni.createFrom().voidItem();
        }
    };

    private final SettingsService service = new SettingsService(repository, mirror, Clock.fixed(NOW, ZoneOffset.UTC));
    private final SettingsEndpoints endpoints = new SettingsEndpoints(service);

    private JsonNode call(RequestHandler handler, String params) throws Exception {
        return mapper.valueToTree(handler.handle(mapper.readTree(params)).await().indefinitely());
    }

    @Test
    void settingsRequestsAreAdministration() {
        assertTrue(endpoints.getSettings().admin());
        assertTrue(endpoints.setMaintenance().admin());
        assertTrue(endpoints.setMinClientVersion().admin());
    }

    @Test
    void maintenanceIsStoredMirroredAndActive() throws Exception {
        JsonNode out = call(endpoints.setMaintenance(),
                "{\"enabled\":true,\"reason\":\" Mise à jour \",\"end\":\"2026-11-22T12:00:00Z\"}");
        assertEquals(200, out.get("status").asInt());
        assertEquals(true, out.at("/payload/maintenance/active").asBoolean());
        assertEquals("Mise à jour", out.at("/payload/maintenance/reason").asText());
        assertEquals("2026-11-22T12:00:00Z", out.at("/payload/maintenance/end").asText());
        assertTrue(out.at("/payload/maintenance/start").isNull());
        assertEquals(stored, mirrored);
        assertNotNull(service.activeMaintenance());
        assertEquals(7200, service.activeMaintenance().secondsLeft(NOW));

        call(endpoints.setMaintenance(), "{\"enabled\":false}");
        assertNull(service.activeMaintenance());
        assertFalse(mirrored.maintenance().enabled());
    }

    @Test
    void aPlannedMaintenanceIsNotActiveYet() throws Exception {
        JsonNode out = call(endpoints.setMaintenance(),
                "{\"enabled\":true,\"reason\":\"Ce soir\",\"start\":\"2026-11-22T20:00:00Z\",\"end\":\"2026-11-22T22:00:00Z\"}");
        assertEquals(true, out.at("/payload/maintenance/enabled").asBoolean());
        assertEquals(false, out.at("/payload/maintenance/active").asBoolean());
        assertNull(service.activeMaintenance());
        Maintenance m = stored.maintenance();
        assertTrue(m.active(Instant.parse("2026-11-22T20:00:00Z")));
        assertFalse(m.active(Instant.parse("2026-11-22T22:00:00Z")));
    }

    @Test
    void wrongMaintenancesAreRefused() throws Exception {
        for (String params : new String[]{"{}", "{\"enabled\":\"yes\"}", "{\"enabled\":true}",
                "{\"enabled\":true,\"reason\":\"   \"}", "{\"enabled\":true,\"reason\":\"x\",\"start\":\"demain\"}",
                "{\"enabled\":true,\"reason\":\"x\",\"start\":\"2026-11-22T12:00:00Z\",\"end\":\"2026-11-22T11:00:00Z\"}",
                "{\"enabled\":true,\"reason\":\"" + "x".repeat(Settings.MAX_REASON + 1) + "\"}"}) {
            assertEquals(400, call(endpoints.setMaintenance(), params).get("status").asInt(), params);
        }
        assertEquals(Settings.DEFAULT, stored);
    }

    @Test
    void minClientVersion() throws Exception {
        JsonNode out = call(endpoints.setMinClientVersion(), "{\"version\":\"0.23\"}");
        assertEquals(200, out.get("status").asInt());
        assertEquals("0.23", out.at("/payload/minClientVersion").asText());
        assertEquals("0.23", mirrored.minClientVersion());
        for (String params : new String[]{"{}", "{\"version\":\"v1\"}", "{\"version\":\"1..2\"}", "{\"version\":12}"}) {
            assertEquals(400, call(endpoints.setMinClientVersion(), params).get("status").asInt(), params);
        }
        assertEquals("0.23", stored.minClientVersion());
    }

    @Test
    void refreshReadsRedisThenTheDatabase() throws Exception {
        stored = stored.withMinClientVersion("1.0", NOW);
        JsonNode out = call(endpoints.getSettings(), "{}");
        // Redis had nothing: read from the database and copied back to Redis
        assertEquals("1.0", out.at("/payload/minClientVersion").asText());
        assertEquals(stored, mirrored);
        // another instance changed Redis
        mirrored = mirrored.withMinClientVersion("1.1", NOW);
        assertEquals("1.1", service.refresh().await().indefinitely().minClientVersion());
        assertEquals("1.1", service.current().minClientVersion());
    }

    @Test
    void theRedisJsonIsReadBack() {
        Settings s = new Settings(new Maintenance(true, "r", Instant.parse("2026-11-22T20:00:00Z"), null), "0.22", NOW);
        assertEquals(s, Settings.fromJson(mapper.valueToTree(s.toMap(NOW))));
        assertEquals(Settings.DEFAULT, Settings.fromJson(mapper.createObjectNode()));
    }
}
