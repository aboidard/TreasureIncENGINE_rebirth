package org.bogomips.treasureInc.engine.sync;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.item.ItemCache;
import org.bogomips.treasureInc.engine.item.ItemInfo;
import org.bogomips.treasureInc.engine.item.ItemList;
import org.bogomips.treasureInc.engine.item.ItemRepository;
import org.bogomips.treasureInc.engine.user.CachedSession;
import org.bogomips.treasureInc.engine.user.UserCache;
import org.bogomips.treasureInc.engine.user.UserLock;
import org.bogomips.treasureInc.engine.user.UserRepository;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    private final UserCache userCache;
    private final ItemCache itemCache;
    private final UserRepository userRepository;
    private final ItemRepository itemRepository;
    private final UserLock lock;
    private final Duration maxAge;
    private final Duration sessionTtl;

    public CacheSyncService(UserCache userCache, ItemCache itemCache, UserRepository userRepository,
                            ItemRepository itemRepository, UserLock lock,
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

    /** Inserts the Redis-only (negative id) items and gives them their database ids in the Redis list. */
    private Uni<Integer> syncItems(String publicKey, int userId) {
        return itemCache.get(publicKey).flatMap(list -> {
            if (list == null || !list.userFound()) {
                return Uni.createFrom().item(0);
            }
            List<ItemInfo> local = list.items().stream().filter(i -> i.id() < 0).toList();
            if (local.isEmpty()) {
                return Uni.createFrom().item(0);
            }
            return itemRepository.insertAll(userId, local).flatMap(newIds -> {
                Map<Integer, Integer> idMap = new HashMap<>();
                for (int i = 0; i < local.size(); i++) {
                    idMap.put(local.get(i).id(), newIds.get(i));
                }
                return remap(publicKey, userId, idMap).replaceWith(local.size());
            });
        });
    }

    /**
     * Re-reads the list: items that vanished meanwhile (e.g. the lock expired) are removed from the database
     * again, the others get their new id.
     */
    private Uni<Void> remap(String publicKey, int userId, Map<Integer, Integer> idMap) {
        return itemCache.get(publicKey).flatMap(fresh -> {
            List<ItemInfo> items = fresh == null ? List.of() : fresh.items();
            Set<Integer> stillThere = new HashSet<>();
            List<ItemInfo> updated = items.stream().map(i -> {
                Integer newId = idMap.get(i.id());
                if (newId == null) {
                    return i;
                }
                stillThere.add(i.id());
                return new ItemInfo(newId, i.name(), i.resolvedDescription(), i.graphics(), i.price(), i.rarity(),
                        i.createdAt(), i.updatedAt());
            }).toList();
            List<Integer> sold = idMap.entrySet().stream()
                    .filter(e -> !stillThere.contains(e.getKey())).map(Map.Entry::getValue).toList();
            Uni<Void> write = fresh == null ? Uni.createFrom().voidItem()
                    : itemCache.put(publicKey, new ItemList(true, updated));
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
