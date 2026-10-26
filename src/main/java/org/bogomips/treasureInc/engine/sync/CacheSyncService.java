package org.bogomips.treasureInc.engine.sync;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.item.ItemInfo;
import org.bogomips.treasureInc.engine.item.ItemService.ItemList;
import org.bogomips.treasureInc.engine.item.ItemService;
import org.bogomips.treasureInc.engine.user.UserService.CachedSession;
import org.bogomips.treasureInc.engine.user.UserService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Writes what only lives in Redis (user balance, generated items) to the database, and evicts idle sessions.
 * A session is synchronized when it has pending changes and its last synchronization is older than {@code maxAge}.
 * A session without pending changes and idle for longer than {@code sessionTtl} is removed from Redis.
 */
@ApplicationScoped
public class CacheSyncService {

    private static final Logger LOG = Logger.getLogger(CacheSyncService.class);

    private final UserService.Cache userCache;
    private final ItemService.Cache itemCache;
    private final UserService.Repository userRepository;
    private final ItemService.Repository itemRepository;
    private final UserService.Lock lock;
    private final Duration maxAge;
    private final Duration sessionTtl;

    public CacheSyncService(UserService.Cache userCache, ItemService.Cache itemCache, UserService.Repository userRepository,
                            ItemService.Repository itemRepository, UserService.Lock lock,
                            @ConfigProperty(name = "engine.sync.max-age", defaultValue = "3m") Duration maxAge,
                            @ConfigProperty(name = "engine.sync.session-ttl", defaultValue = "1h") Duration sessionTtl) {
        this.userCache = userCache;
        this.itemCache = itemCache;
        this.userRepository = userRepository;
        this.itemRepository = itemRepository;
        this.lock = lock;
        this.maxAge = maxAge;
        this.sessionTtl = sessionTtl;
    }

    /** Runs {@link #syncDue} every {@code engine.sync.check-interval} (default 30s). */
    @Scheduled(every = "{engine.sync.check-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            identity = "redis-to-database-sync")
    Uni<Void> run() {
        return syncDue(Instant.now())
                .invoke(n -> {
                    if (n > 0) {
                        LOG.infof("event=sync_completed users=%d", n);
                    }
                })
                .onFailure().invoke(t -> LOG.errorf(t, "event=sync_run_failed reason=%s", t.toString()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * Walks the sessions incrementally (never all in memory), one at a time. Returns how many were synchronized.
     * A failing user is logged and retried at the next run.
     */
    public Uni<Integer> syncDue(Instant now) {
        return userCache.scan()
                .select().where(s -> isDue(s, now) || isIdle(s, now))
                .onItem().transformToUniAndConcatenate(s -> process(s.user().publicKey(), now)
                        .onFailure().recoverWithItem(t -> {
                            LOG.errorf(t, "event=sync_failed user=%s reason=%s", s.user().publicKey(), t.toString());
                            return 0;
                        }))
                .collect().with(Collectors.summingInt(Integer::intValue));
    }

    private boolean isDue(CachedSession s, Instant now) {
        return s.dirty() && s.lastSyncAt().plus(maxAge).isBefore(now);
    }

    private boolean isIdle(CachedSession s, Instant now) {
        return !s.dirty() && s.lastActionAt().plus(sessionTtl).isBefore(now);
    }

    /** Runs under the user's lock on a fresh copy of the session, since a scanned entry may be outdated. */
    private Uni<Integer> process(String publicKey, Instant now) {
        return lock.withLock(publicKey, () -> userCache.get(publicKey).flatMap(fresh -> {
            if (fresh == null) {
                return Uni.createFrom().item(0);
            }
            boolean due = isDue(fresh, now);
            int synced = due ? 1 : 0;
            Uni<Void> write = due ? sync(fresh) : Uni.createFrom().voidItem();
            // idle is checked on the pre-sync copy: syncing does not count as activity
            boolean evictable = (due || !fresh.dirty()) && fresh.lastActionAt().plus(sessionTtl).isBefore(now);
            return write.flatMap(v -> evictable ? evict(publicKey) : Uni.createFrom().voidItem())
                    .replaceWith(synced);
        }));
    }

    private Uni<Void> evict(String publicKey) {
        // the database holds everything by now, so the items list can be dropped too
        return itemCache.evict(publicKey).flatMap(v -> userCache.evict(publicKey))
                .invoke(() -> LOG.debugf("event=session_evicted user=%s idleLongerThan=%s", publicKey, sessionTtl));
    }

    private Uni<Void> sync(CachedSession session) {
        String publicKey = session.user().publicKey();
        int userId = session.user().id();
        int money = session.user().money();
        return userRepository.updateMoney(userId, money)
                .flatMap(v -> syncItems(publicKey, userId))
                .flatMap(inserted -> markSynced(publicKey, money)
                        .invoke(() -> LOG.debugf("event=sync_done user=%s money=%d itemsInserted=%d",
                                publicKey, money, inserted)));
    }

    /** Inserts the pending items with the ids they already have, so the client's ids stay valid. */
    private Uni<Integer> syncItems(String publicKey, int userId) {
        return itemCache.get(publicKey).flatMap(list -> {
            if (list == null || !list.userFound() || list.pendingIds().isEmpty()) {
                return Uni.createFrom().item(0);
            }
            List<ItemInfo> pending = list.items().stream().filter(i -> list.pendingIds().contains(i.id())).toList();
            Set<Integer> ids = pending.stream().map(ItemInfo::id).collect(Collectors.toSet());
            return itemRepository.insertAll(userId, pending)
                    .flatMap(v -> markInserted(publicKey, userId, ids))
                    .replaceWith(pending.size());
        });
    }

    /**
     * Re-reads the list: the inserted items are no longer pending, and those that vanished meanwhile (e.g. sold
     * after the lock expired) are removed from the database again.
     */
    private Uni<Void> markInserted(String publicKey, int userId, Set<Integer> inserted) {
        return itemCache.get(publicKey).flatMap(fresh -> {
            if (fresh == null) {
                return itemRepository.deleteByIds(userId, List.copyOf(inserted));
            }
            Set<Integer> stillThere = fresh.items().stream().map(ItemInfo::id).filter(inserted::contains)
                    .collect(Collectors.toSet());
            List<Integer> sold = inserted.stream().filter(id -> !stillThere.contains(id)).toList();
            Set<Integer> pending = fresh.pendingIds().stream().filter(id -> !inserted.contains(id))
                    .collect(Collectors.toSet());
            ItemList updated = new ItemList(true, fresh.items(), pending);
            // the list may expire again once nothing in it is missing from the database
            Uni<Void> write = pending.isEmpty() ? itemCache.put(publicKey, updated) : itemCache.persist(publicKey, updated);
            return write.flatMap(v -> itemRepository.deleteByIds(userId, sold));
        });
    }


    /** Stays dirty if the balance moved while we were writing (only possible if the lock expired). */
    private Uni<Void> markSynced(String publicKey, int syncedMoney) {
        return userCache.get(publicKey).flatMap(current -> {
            if (current == null) {
                return Uni.createFrom().voidItem();
            }
            boolean changed = current.user().money() != syncedMoney;
            return userCache.put(publicKey, current.synced(Instant.now(), changed));
        });
    }
}
