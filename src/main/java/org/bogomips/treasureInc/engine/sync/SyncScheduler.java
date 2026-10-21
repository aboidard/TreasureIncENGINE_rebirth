package org.bogomips.treasureInc.engine.sync;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;

/** Triggers {@link CacheSyncService} every {@code engine.sync.check-interval} (default 30s). */
@ApplicationScoped
public class SyncScheduler {

    private static final Logger LOG = Logger.getLogger(SyncScheduler.class);

    private final CacheSyncService sync;

    public SyncScheduler(CacheSyncService sync) {
        this.sync = sync;
    }

    @Scheduled(every = "{engine.sync.check-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            identity = "redis-to-database-sync")
    Uni<Void> run() {
        return sync.syncDue(Instant.now())
                .invoke(n -> {
                    if (n > 0) {
                        LOG.infof("event=sync_completed users=%d", n);
                    }
                })
                .onFailure().invoke(t -> LOG.errorf(t, "event=sync_run_failed reason=%s", t.toString()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }
}
