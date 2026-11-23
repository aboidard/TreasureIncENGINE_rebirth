package org.bogomips.treasureInc.engine.settings;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle;
import io.smallrye.common.vertx.VertxContext;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.bogomips.treasureInc.engine.settings.Settings.Maintenance;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;

/**
 * The settings of the game (maintenance, oldest client version), changed from Maestro.
 * <p>
 * The database (table app_settings) keeps them; Redis mirrors them ({@link Mirror}) so that the API reads them without
 * the engine, which may be stopped during a maintenance. Every engine instance keeps a copy in memory, read on every
 * request by the {@code RequestDispatcher}, refreshed from Redis every {@code engine.settings.refresh}: a change made
 * on one instance reaches the others within that time. When Redis has lost them, they are read again from the database.
 */
@ApplicationScoped
public class SettingsService {

    private static final Logger LOG = Logger.getLogger(SettingsService.class);

    /** The settings row; there is only one. */
    public interface Repository {
        /** The stored settings, {@link Settings#DEFAULT} when none were ever stored. */
        Uni<Settings> load();

        /** Stores the maintenance and answers the settings as stored. */
        Uni<Settings> saveMaintenance(Maintenance maintenance, Instant at);

        /** Stores the oldest client version and answers the settings as stored. */
        Uni<Settings> saveMinClientVersion(String version, Instant at);
    }

    /** The copy of the settings in Redis, read by the API. */
    public interface Mirror {
        /** Null when Redis has no copy. */
        Uni<Settings> read();

        Uni<Void> write(Settings settings);
    }

    private final Repository repository;
    private final Mirror mirror;
    private final Clock clock;
    private volatile Settings current = Settings.DEFAULT;

    @Inject
    public SettingsService(Repository repository, Mirror mirror) {
        this(repository, mirror, Clock.systemUTC());
    }

    public SettingsService(Repository repository, Mirror mirror, Clock clock) {
        this.repository = repository;
        this.mirror = mirror;
        this.clock = clock;
    }

    /** The settings as this instance knows them (no I/O). */
    public Settings current() {
        return current;
    }

    /** The maintenance under way now, or null (no I/O). */
    public Maintenance activeMaintenance() {
        Maintenance m = current.maintenance();
        return m.active(clock.instant()) ? m : null;
    }

    public Instant now() {
        return clock.instant();
    }

    /** Reads the settings from Redis, or from the database (copied back to Redis) when Redis has none. */
    public Uni<Settings> refresh() {
        return mirror.read()
                .onFailure().recoverWithItem(t -> {
                    LOG.warnf("event=settings_mirror_read_failed reason=%s", t.toString());
                    return null;
                })
                .flatMap(s -> s != null
                        ? Uni.createFrom().item(s)
                        : repository.load().call(mirror::write))
                .invoke(this::remember);
    }

    public Uni<Settings> setMaintenance(Maintenance maintenance) {
        return repository.saveMaintenance(maintenance, clock.instant()).call(mirror::write).invoke(this::remember);
    }

    public Uni<Settings> setMinClientVersion(String version) {
        return repository.saveMinClientVersion(version, clock.instant()).call(mirror::write).invoke(this::remember);
    }

    private void remember(Settings s) {
        Settings before = current;
        current = s;
        if (!s.maintenance().equals(before.maintenance()) || !s.minClientVersion().equals(before.minClientVersion())) {
            LOG.infof("event=settings_changed maintenance=%s active=%s minClientVersion=%s",
                    s.maintenance(), s.maintenance().active(clock.instant()), s.minClientVersion());
        }
    }

    // ---- refresh at startup and every engine.settings.refresh

    @Inject
    Vertx vertx;

    void onStart(@Observes StartupEvent ev) {
        // Hibernate Reactive needs a safe duplicated Vert.x context
        io.vertx.core.Context ctx = VertxContext.getOrCreateDuplicatedContext(vertx.getOrCreateContext().getDelegate());
        VertxContextSafetyToggle.setContextSafe(ctx, true);
        ctx.runOnContext(v -> refresh().subscribe().with(
                s -> LOG.infof("event=settings_loaded maintenance=%s minClientVersion=%s",
                        s.maintenance(), s.minClientVersion()),
                t -> LOG.errorf("event=settings_load_failed reason=%s", t.toString())));
    }

    @Scheduled(every = "{engine.settings.refresh}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            identity = "settings-refresh")
    Uni<Void> scheduledRefresh() {
        return refresh()
                .onFailure().invoke(t -> LOG.warnf("event=settings_refresh_failed reason=%s", t.toString()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }
}
