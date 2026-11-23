package org.bogomips.treasureInc.engine.events;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle;
import io.smallrye.common.vertx.VertxContext;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The events: expeditions limited in time or special, changed from Maestro.
 * <p>
 * The database (table game_events) keeps them. When the engine starts, they are read from the database and written to
 * Redis ({@link Mirror}); every engine instance keeps a copy in memory, refreshed from Redis every
 * {@code engine.events.refresh}, so that a change made from Maestro on one instance reaches the others. When Redis has
 * lost them, they are read again from the database.
 * <p>
 * How many expeditions a player sent to an event today ({@link Runs}) is counted in Redis, the day starting at
 * midnight in the zone of the quests.
 */
@ApplicationScoped
public class EventService {

    private static final Logger LOG = Logger.getLogger(EventService.class);

    /** The table game_events. */
    public interface Repository {
        Uni<List<GameEvent>> load();

        /** Creates or replaces the event of this code; answers it as stored. */
        Uni<GameEvent> save(GameEvent event);

        /** False when there was no such event. */
        Uni<Boolean> delete(String code);
    }

    /** The copy of the events in Redis. */
    public interface Mirror {
        /** Null when Redis has no copy. */
        Uni<List<GameEvent>> read();

        Uni<Void> write(List<GameEvent> events);
    }

    /** How many expeditions each player sent to each event, per day. */
    public interface Runs {
        Uni<Integer> count(String publicKey, String code, LocalDate day);

        /** Counts one more; answers the new count. */
        Uni<Integer> add(String publicKey, String code, LocalDate day);
    }

    private final Repository repository;
    private final Mirror mirror;
    private final Runs runs;
    private final Clock clock;
    private volatile List<GameEvent> current = List.of();

    @Inject
    public EventService(Repository repository, Mirror mirror, Runs runs,
                        @ConfigProperty(name = "engine.quests.zone", defaultValue = "Europe/Paris") String zone) {
        this(repository, mirror, runs, Clock.system(ZoneId.of(zone)));
    }

    /** @param clock gives the time and the zone whose midnight starts a new day */
    public EventService(Repository repository, Mirror mirror, Runs runs, Clock clock) {
        this.repository = repository;
        this.mirror = mirror;
        this.runs = runs;
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    /** Every event as this instance knows them (no I/O), by sortOrder then code. */
    public List<GameEvent> all() {
        return current;
    }

    /** The events players may go to now (no I/O). */
    public List<GameEvent> open() {
        Instant now = clock.instant();
        return current.stream().filter(e -> e.open(now)).toList();
    }

    /** The event of this code if it is open now; null otherwise (no I/O). */
    public GameEvent openEvent(String code) {
        Instant now = clock.instant();
        return current.stream().filter(e -> e.code().equals(code) && e.open(now)).findFirst().orElse(null);
    }

    /** The event of this code, open or not (no I/O); null when there is none. */
    public GameEvent find(String code) {
        return current.stream().filter(e -> e.code().equals(code)).findFirst().orElse(null);
    }

    /** How many expeditions the player sent to the event today. */
    public Uni<Integer> runsToday(String publicKey, String code) {
        return runs.count(publicKey, code, today());
    }

    /** How many expeditions the player sent today to each of the events, by code. */
    public Uni<Map<String, Integer>> runsToday(String publicKey, List<GameEvent> events) {
        if (events.isEmpty()) {
            return Uni.createFrom().item(Map.of());
        }
        List<Uni<Map.Entry<String, Integer>>> counts = events.stream()
                .map(e -> runsToday(publicKey, e.code()).map(n -> Map.entry(e.code(), n)))
                .toList();
        return Uni.join().all(counts).andFailFast()
                .map(list -> list.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    /** Counts an expedition sent to the event today; call it inside the user's lock, once the expedition is sent. */
    public Uni<Integer> countRun(String publicKey, String code) {
        return runs.add(publicKey, code, today());
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Reads the events from Redis, or from the database (copied back to Redis) when Redis has none. */
    public Uni<List<GameEvent>> refresh() {
        return mirror.read()
                .onFailure().recoverWithItem(t -> {
                    LOG.warnf("event=events_mirror_read_failed reason=%s", t.toString());
                    return null;
                })
                .flatMap(events -> events != null ? Uni.createFrom().item(events) : reload())
                .map(this::remember);
    }

    /** Reads the events from the database and writes them to Redis. */
    public Uni<List<GameEvent>> reload() {
        return repository.load().call(mirror::write).map(this::remember);
    }

    /** Creates or replaces an event; every instance knows it within engine.events.refresh. */
    public Uni<GameEvent> save(GameEvent event) {
        return repository.save(event.withUpdatedAt(clock.instant()))
                .call(saved -> mirror.write(replace(saved)).invoke(() -> remember(replace(saved))));
    }

    /** False when there was no such event. */
    public Uni<Boolean> delete(String code) {
        return repository.delete(code).call(deleted -> {
            List<GameEvent> left = current.stream().filter(e -> !e.code().equals(code)).toList();
            return mirror.write(left).invoke(() -> remember(left));
        });
    }

    private List<GameEvent> replace(GameEvent saved) {
        List<GameEvent> events = new ArrayList<>(current.stream().filter(e -> !e.code().equals(saved.code())).toList());
        events.add(saved);
        return events;
    }

    private List<GameEvent> remember(List<GameEvent> events) {
        List<GameEvent> sorted = events.stream()
                .sorted(Comparator.comparingInt(GameEvent::sortOrder).thenComparing(GameEvent::code))
                .toList();
        Map<String, GameEvent> before = current.stream()
                .collect(Collectors.toMap(GameEvent::code, Function.identity()));
        current = sorted;
        boolean changed = sorted.size() != before.size()
                || sorted.stream().anyMatch(e -> !e.equals(before.get(e.code())));
        if (changed) {
            LOG.infof("event=events_changed codes=%s open=%s", sorted.stream().map(GameEvent::code).toList(),
                    open().stream().map(GameEvent::code).toList());
        }
        return sorted;
    }

    // ---- loaded from the database at startup, refreshed from Redis every engine.events.refresh

    @Inject
    Vertx vertx;

    void onStart(@Observes StartupEvent ev) {
        // Hibernate Reactive needs a safe duplicated Vert.x context
        io.vertx.core.Context ctx = VertxContext.getOrCreateDuplicatedContext(vertx.getOrCreateContext().getDelegate());
        VertxContextSafetyToggle.setContextSafe(ctx, true);
        ctx.runOnContext(v -> reload().subscribe().with(
                events -> LOG.infof("event=events_loaded count=%d", events.size()),
                t -> LOG.errorf("event=events_load_failed reason=%s", t.toString())));
    }

    @Scheduled(every = "{engine.events.refresh}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            identity = "events-refresh")
    Uni<Void> scheduledRefresh() {
        return refresh()
                .onFailure().invoke(t -> LOG.warnf("event=events_refresh_failed reason=%s", t.toString()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }
}
