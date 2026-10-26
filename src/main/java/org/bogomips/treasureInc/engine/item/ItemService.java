package org.bogomips.treasureInc.engine.item;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.user.UserService;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

@ApplicationScoped
public class ItemService {

    private final Repository repository;
    private final Cache itemCache;
    private final UserService users;
    private final UserService.Lock lock;
    private final ItemCatalog catalog;
    private final int idBlockSize;

    /**
     * @param graphicsCount number of item sprites in the client (ItemManager.sprites); a graphics id is below it
     * @param idBlockSize   how many ids are reserved from the database sequence at once
     */
    public ItemService(Repository repository, Cache itemCache, UserService users, UserService.Lock lock,
                       @ConfigProperty(name = "engine.items.graphics-count") int graphicsCount,
                       @ConfigProperty(name = "engine.items.id-block-size", defaultValue = "100") int idBlockSize) {
        if (idBlockSize < 1) {
            throw new IllegalArgumentException("engine.items.id-block-size must be at least 1, got " + idBlockSize);
        }
        this.repository = repository;
        this.itemCache = itemCache;
        this.users = users;
        this.lock = lock;
        this.catalog = new ItemCatalog(RandomGenerator.getDefault(), graphicsCount);
        this.idBlockSize = idBlockSize;
    }

    /** Served from Redis when present (including the "unknown user" marker); the database is only hit on a miss. */
    public Uni<ItemList> list(String publicKey) {
        return itemCache.get(publicKey).flatMap(cached -> {
            if (cached != null) {
                return Uni.createFrom().item(cached);
            }
            return repository.findByPublicKey(publicKey)
                    .flatMap(list -> itemCache.put(publicKey, list).replaceWith(list));
        });
    }

    /** 1-based page; emits null when the public key is unknown. */
    public Uni<Page> page(String publicKey, int page, int limit) {
        return list(publicKey).map(list -> {
            if (!list.userFound()) {
                return null;
            }
            List<ItemInfo> all = list.items();
            long from = (long) (page - 1) * limit;
            List<ItemInfo> slice = from >= all.size() ? List.of()
                    : all.subList((int) from, (int) Math.min(all.size(), from + limit));
            return new Page(slice, page, limit, all.size());
        });
    }

    /**
     * Generates items straight into the user's Redis list (no database write), with their final ids, pending until
     * the next synchronization inserts them; emits null on bad credentials.
     * The list is kept without expiry since Redis is then its only copy.
     */
    public Uni<List<ItemInfo>> generate(String publicKey, String privateKey, int count) {
        return lock.withLock(publicKey, () -> generateLocked(publicKey, privateKey, count));
    }

    private Uni<List<ItemInfo>> generateLocked(String publicKey, String privateKey, int count) {
        return users.openSession(publicKey, privateKey).flatMap(user -> {
            if (user == null) {
                return Uni.createFrom().nullItem();
            }
            return newIds(count).flatMap(ids -> {
                List<ItemInfo> generated = ids.stream().map(catalog::generate).toList();
                return list(publicKey).flatMap(current -> {
                    List<ItemInfo> all = new ArrayList<>(current.userFound() ? current.items() : List.of());
                    all.addAll(generated);
                    Set<Integer> pending = new HashSet<>(current.pendingIds());
                    pending.addAll(ids);
                    return itemCache.persist(publicKey, new ItemList(true, all, pending))
                            .flatMap(v -> users.markDirty(publicKey))
                            .replaceWith(generated);
                });
            });
        });
    }

    /**
     * Final ids for new items, so an item keeps its id once written to the database. They come from the pool of
     * reserved ids; when it runs low, a block of {@code idBlockSize} values is taken from the database sequence in
     * one call, and what is not used now goes to the pool.
     */
    private Uni<List<Integer>> newIds(int count) {
        return itemCache.takeIds(count).flatMap(pooled -> {
            int missing = count - pooled.size();
            if (missing == 0) {
                return Uni.createFrom().item(pooled);
            }
            return repository.nextIds(Math.max(idBlockSize, missing)).flatMap(reserved -> {
                List<Integer> ids = new ArrayList<>(pooled);
                ids.addAll(reserved.subList(0, missing));
                List<Integer> spare = reserved.subList(missing, reserved.size());
                Uni<Void> keep = spare.isEmpty() ? Uni.createFrom().voidItem() : itemCache.addIds(spare);
                return keep.replaceWith(ids);
            });
        });
    }

    /**
     * The whole operation runs under the user's lock, so two concurrent sells/generations cannot overwrite each other.
     * Sells from the Redis list. Items are validated against the cached list; pending items (not yet inserted)
     * never touch the database. The database is only called when database items are sold, or when the user
     * has no cached session to credit (the pending items' value is then added in the same transaction).
     */
    public Uni<SellResult> sell(String publicKey, String privateKey, List<Integer> ids) {
        return lock.withLock(publicKey, () -> sellLocked(publicKey, privateKey, ids));
    }

    private Uni<SellResult> sellLocked(String publicKey, String privateKey, List<Integer> ids) {
        return users.authenticate(publicKey, privateKey).flatMap(user -> {
            if (user == null) {
                return Uni.createFrom().item(SellResult.of(SellResult.Status.UNAUTHORIZED));
            }
            return list(publicKey).flatMap(list -> {
                Map<Integer, ItemInfo> byId = new HashMap<>();
                list.items().forEach(i -> byId.put(i.id(), i));
                if (!byId.keySet().containsAll(ids)) {
                    return Uni.createFrom().item(SellResult.of(SellResult.Status.NOT_FOUND));
                }
                Set<Integer> pending = list.pendingIds();
                List<Integer> dbIds = ids.stream().filter(id -> !pending.contains(id)).toList();
                int redisTotal = ids.stream().filter(pending::contains).mapToInt(id -> price(byId.get(id))).sum();
                if (dbIds.isEmpty()) {
                    return sellRedisOnly(publicKey, privateKey, ids, redisTotal);
                }
                return sellWithDatabase(publicKey, privateKey, ids, dbIds, redisTotal);
            });
        });
    }

    private Uni<SellResult> sellRedisOnly(String publicKey, String privateKey, List<Integer> ids, int total) {
        return users.credit(publicKey, total, true).flatMap(updated -> {
            if (updated == null) {
                return sellWithDatabase(publicKey, privateKey, ids, List.of(), total);
            }
            return itemCache.removeIds(publicKey, ids)
                    .replaceWith(new SellResult(SellResult.Status.OK, total, updated.money(), ids));
        });
    }

    private Uni<SellResult> sellWithDatabase(String publicKey, String privateKey, List<Integer> ids,
                                             List<Integer> dbIds, int extraMoney) {
        return repository.sell(publicKey, privateKey, dbIds, extraMoney).flatMap(result -> {
            if (result.status() != SellResult.Status.OK) {
                return Uni.createFrom().item(result);
            }
            // The database has just been credited: add the same amount to the cache rather than overwriting it,
            // as the cached balance may hold credits the database has not received yet.
            return itemCache.removeIds(publicKey, ids)
                    .flatMap(v -> users.credit(publicKey, result.totalPrice(), false))
                    .map(updated -> new SellResult(SellResult.Status.OK, result.totalPrice(),
                            updated == null ? result.money() : updated.money(), ids));
        });
    }

    private static int price(ItemInfo item) {
        return item.price() == null ? 0 : item.price();
    }

    // ---- values

    /**
     * All the items of a user; userFound=false is a cached "unknown public key" marker.
     * pendingIds are the generated items not yet written to the database (the next synchronization inserts them,
     * keeping their id).
     */
    public record ItemList(
            @JsonProperty("userFound") boolean userFound,
            @JsonProperty("items") List<ItemInfo> items,
            @JsonProperty("pendingIds") Set<Integer> pendingIds) {

        public ItemList {
            pendingIds = pendingIds == null ? Set.of() : Set.copyOf(pendingIds);
        }

        /** A list with nothing waiting for the database, as read from it. */
        public ItemList(boolean userFound, List<ItemInfo> items) {
            this(userFound, items, Set.of());
        }

        public static ItemList unknownUser() {
            return new ItemList(false, List.of());
        }
    }

    public record Page(List<ItemInfo> items, int page, int limit, int total) {
    }

    public record SellResult(Status status, int totalPrice, int money, List<Integer> soldIds) {

        public enum Status { OK, UNAUTHORIZED, NOT_FOUND }

        public static SellResult of(Status status) {
            return new SellResult(status, 0, 0, List.of());
        }
    }

    // ---- ports, implemented by the Panache and Redis adapters

    public interface Repository {
        /** All items of the user ordered by id; ItemList.unknownUser() when the public key does not exist. */
        Uni<ItemList> findByPublicKey(String publicKey);

        /**
         * Atomically deletes the database items (all must belong to the user; ids may be empty) and credits their
         * price to the user's money; extraMoney is the value of pending (not yet inserted) items sold alongside.
         */
        Uni<SellResult> sell(String publicKey, String privateKey, List<Integer> ids, int extraMoney);

        /** Takes {@code count} values from the items id sequence in one round trip: unique, not always consecutive. */
        Uni<List<Integer>> nextIds(int count);

        /** Inserts pending items for the user, with the ids they already have. */
        Uni<Void> insertAll(int userId, List<ItemInfo> items);

        Uni<Void> deleteByIds(int userId, List<Integer> ids);
    }

    public interface Cache {
        /** Null when nothing is cached for this public key. */
        Uni<ItemList> get(String publicKey);

        Uni<Void> put(String publicKey, ItemList list);

        /** Stores the list without expiry: used while it holds pending items, which have no database copy. */
        Uni<Void> persist(String publicKey, ItemList list);

        /** Takes up to {@code count} ids from the pool of reserved ids, shared by every engine instance; fewer (or none) when it runs low. */
        Uni<List<Integer>> takeIds(int count);

        /** Puts reserved ids, taken from the database sequence, in the pool. */
        Uni<Void> addIds(List<Integer> ids);

        /** Removes the given item ids from the cached list (items and pending ids), if any. */
        Uni<Void> removeIds(String publicKey, Collection<Integer> ids);

        /** Drops the cached list, whatever its expiry. */
        Uni<Void> evict(String publicKey);
    }
}
