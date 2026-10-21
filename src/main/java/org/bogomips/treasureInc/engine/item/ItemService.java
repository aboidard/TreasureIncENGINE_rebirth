package org.bogomips.treasureInc.engine.item;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.user.UserService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

@ApplicationScoped
public class ItemService {

    private final Repository repository;
    private final Cache itemCache;
    private final UserService users;
    private final ItemGenerator generator = new ItemGenerator(RandomGenerator.getDefault());

    public ItemService(Repository repository, Cache itemCache, UserService users) {
        this.repository = repository;
        this.itemCache = itemCache;
        this.users = users;
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
     * Generates items straight into the user's Redis list (no database write); emits null on bad credentials.
     * The list is kept without expiry since Redis is then its only copy.
     */
    public Uni<List<ItemInfo>> generate(String publicKey, String privateKey, int count) {
        return users.withLock(publicKey, () -> generateLocked(publicKey, privateKey, count));
    }

    private Uni<List<ItemInfo>> generateLocked(String publicKey, String privateKey, int count) {
        return users.openSession(publicKey, privateKey).flatMap(user -> {
            if (user == null) {
                return Uni.createFrom().nullItem();
            }
            return itemCache.reserveIds(count).flatMap(firstId -> {
                List<ItemInfo> generated = IntStream.range(0, count).mapToObj(i -> generator.generate(firstId + i)).toList();
                return list(publicKey).flatMap(current -> {
                    List<ItemInfo> all = new ArrayList<>(current.userFound() ? current.items() : List.of());
                    all.addAll(generated);
                    return itemCache.persist(publicKey, new ItemList(true, all))
                            .flatMap(v -> users.markDirty(publicKey))
                            .replaceWith(generated);
                });
            });
        });
    }

    /**
     * The whole operation runs under the user's lock, so two concurrent sells/generations cannot overwrite each other.
     * Sells from the Redis list. Items are validated against the cached list; Redis-only items (negative ids)
     * never touch the database. The database is only called when database items are sold, or when the user
     * has no cached session to credit (the Redis-only value is then added in the same transaction).
     */
    public Uni<SellResult> sell(String publicKey, String privateKey, List<Integer> ids) {
        return users.withLock(publicKey, () -> sellLocked(publicKey, privateKey, ids));
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
                List<Integer> dbIds = ids.stream().filter(id -> id >= 0).toList();
                int redisTotal = ids.stream().filter(id -> id < 0).mapToInt(id -> price(byId.get(id))).sum();
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

    /** All the items of a user; userFound=false is a cached "unknown public key" marker. */
    public record ItemList(
            @JsonProperty("userFound") boolean userFound,
            @JsonProperty("items") List<ItemInfo> items) {

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
         * price to the user's money; extraMoney is the value of Redis-only items sold alongside.
         */
        Uni<SellResult> sell(String publicKey, String privateKey, List<Integer> ids, int extraMoney);

        /** Inserts Redis-only items for the user; emits their new database ids, in the same order. */
        Uni<List<Integer>> insertAll(int userId, List<ItemInfo> items);

        Uni<Void> deleteByIds(int userId, List<Integer> ids);
    }

    public interface Cache {
        /** Null when nothing is cached for this public key. */
        Uni<ItemList> get(String publicKey);

        Uni<Void> put(String publicKey, ItemList list);

        /** Stores the list without expiry: used for Redis-only items, which have no database copy. */
        Uni<Void> persist(String publicKey, ItemList list);

        /** Reserves {@code count} consecutive ids for Redis-only items (negative, so they never clash with database ids); returns the first. */
        Uni<Integer> reserveIds(int count);

        /** Removes the given item ids from the cached list, if any. */
        Uni<Void> removeIds(String publicKey, Collection<Integer> ids);

        /** Drops the cached list, whatever its expiry. */
        Uni<Void> evict(String publicKey);
    }
}
