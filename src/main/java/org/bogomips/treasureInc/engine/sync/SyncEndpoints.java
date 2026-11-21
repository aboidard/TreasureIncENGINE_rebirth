package org.bogomips.treasureInc.engine.sync;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.bogomips.treasureInc.engine.messaging.Protocol;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Administration request types, sent by Maestro straight on the Redis request channel.
 * The API only relays the request types of the game, so a player cannot reach them.
 */
@ApplicationScoped
class SyncEndpoints {

    private static final Logger LOG = Logger.getLogger(SyncEndpoints.class);

    private final CacheSyncService sync;

    SyncEndpoints(CacheSyncService sync) {
        this.sync = sync;
    }

    @Produces
    RequestHandler forceSync() {
        return RequestHandler.of("forceSync", this::forceSync);
    }

    private Uni<Object> forceSync(JsonNode params) {
        Instant startedAt = Instant.now();
        long start = System.nanoTime();
        return sync.syncAll().map(report -> {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            LOG.infof("event=forced_sync_completed users=%d errors=%d durationMs=%d",
                    report.syncedUsers(), report.errors(), durationMs);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("startedAt", startedAt.toString());
            payload.put("durationMs", durationMs);
            payload.put("syncedUsers", report.syncedUsers());
            payload.put("errors", report.errors());
            return Protocol.reply(200, "OK", payload);
        });
    }
}
