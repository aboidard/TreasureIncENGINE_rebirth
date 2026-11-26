package org.bogomips.treasureInc.engine.item;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bogomips.treasureInc.engine.quest.QuestCatalog.Metric;
import org.bogomips.treasureInc.engine.quest.QuestTracker;
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
import java.util.stream.Collectors;

@ApplicationScoped
public class ItemService {

    private final Repository repository;
    private final Cache itemCache;
    private final UserService users;
    private final UserService.Lock lock;
    private final Equipped equipped;
    private final ItemCatalog catalog;
    private final int idBlockSize;
    private final QuestTracker quests;

    /** An item service that counts nothing for the quests. */
    public ItemService(Repository repository, Cache itemCache, UserService users, UserService.Lock lock,
                       Equipped equipped, int graphicsCount, int idBlockSize, double equipableChance) {
        this(repository, itemCache, users, lock, equipped, graphicsCount, idBlockSize, equipableChance,
                QuestTracker.NONE);
    }

    /**
     * @param graphicsCount number of item sprites in the client (ItemManager.sprites); a graphics id is below it
     * @param idBlockSize   how many ids are reserved from the database sequence at once
     * @param equipableChance chance, in percent, that a generated item is an equipment
     * @param quests        where the items sold are counted
     */
    @Inject
    public ItemService(Repository repository, Cache itemCache, UserService users, UserService.Lock lock,
                       Equipped equipped,
                       @ConfigProperty(name = "engine.items.graphics-count") int graphicsCount,
                       @ConfigProperty(name = "engine.items.id-block-size", defaultValue = "100") int idBlockSize,
                       @ConfigProperty(name = "engine.items.equipable-chance", defaultValue = "5") double equipableChance,
                       QuestTracker quests) {
        this.quests = quests;
        if (idBlockSize < 1) {
            throw new IllegalArgumentException("engine.items.id-block-size must be at least 1, got " + idBlockSize);
        }
        this.repository = repository;
        this.itemCache = itemCache;
        this.users = users;
        this.lock = lock;
        this.equipped = equipped;
        this.catalog = new ItemCatalog(RandomGenerator.getDefault(), graphicsCount, equipableChance);
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

    /**
     * 1-based page of the inventory, which leaves out the items equipped on a character; emits null when the
     * public key is unknown.
     */
    public Uni<Page> page(String publicKey, int page, int limit) {
        return list(publicKey).flatMap(list -> !list.userFound() ? Uni.createFrom().nullItem()
                : equipped.ids(publicKey).map(worn -> page(inventory(list, worn), page, limit)));
    }

    private static List<ItemInfo> inventory(ItemList list, Set<Integer> worn) {
        return worn.isEmpty() ? list.items() : list.items().stream().filter(i -> !worn.contains(i.id())).toList();
    }

    private static Page page(List<ItemInfo> all, int page, int limit) {
        long from = (long) (page - 1) * limit;
        List<ItemInfo> slice = from >= all.size() ? List.of()
                : all.subList((int) from, (int) Math.min(all.size(), from + limit));
        return new Page(slice, page, limit, all.size());
    }

    /**
     * Generates items straight into the user's Redis list (no database write), with their final ids, pending until
     * the next synchronization inserts them; emits null on bad credentials.
     * The list is kept without expiry since Redis is then its only copy.
     * Call it inside the user's {@link UserService.Lock}.
     */
    public Uni<List<ItemInfo>> generateLocked(String publicKey, String privateKey, int count) {
        return generateLocked(publicKey, privateKey, count, (String) null);
    }

    /**
     * Like {@link #generateLocked(String, String, int)}, every item having this rarity ("common"... "legendary",
     * see {@link #isRarity}); a null rarity is drawn for each item as usual.
     */
    public Uni<List<ItemInfo>> generateLocked(String publicKey, String privateKey, int count, String rarityValue) {
        ItemCatalog.Rarity rarity = rarityValue == null ? null : ItemCatalog.Rarity.fromValue(rarityValue);
        return generateLocked(publicKey, privateKey, count,
                id -> rarity == null ? catalog.generate(id) : catalog.generate(id, rarity));
    }

    /**
     * Like {@link #generateLocked(String, String, int)}, the rarity of each item being drawn from these chances, in
     * per mille from common to legendary (an expedition's chest: see {@link ItemCatalog.Rarity#fromRoll(int, List)}).
     */
    public Uni<List<ItemInfo>> generateLocked(String publicKey, String privateKey, int count,
                                              List<Integer> rarityWeights) {
        return generateLocked(publicKey, privateKey, count, id -> catalog.generate(id, rarityWeights));
    }

    private Uni<List<ItemInfo>> generateLocked(String publicKey, String privateKey, int count,
                                               java.util.function.IntFunction<ItemInfo> generator) {
        return users.openSession(publicKey, privateKey).flatMap(user -> {
            if (user == null) {
                return Uni.createFrom().nullItem();
            }
            if (count <= 0) {
                // an empty chest: nothing to reserve (Redis refuses to take zero ids) nor to write
                return Uni.createFrom().item(List.<ItemInfo>of());
            }
            return newIds(count).flatMap(ids -> {
                List<ItemInfo> generated = ids.stream().map(generator::apply).toList();
                return list(publicKey).flatMap(current -> {
                    List<ItemInfo> all = new ArrayList<>(current.userFound() ? current.items() : List.of());
                    all.addAll(generated);
                    Set<Integer> pending = new HashSet<>(current.pendingIds());
                    pending.addAll(ids);
                    return itemCache.persist(publicKey, new ItemList(true, all, pending, current.insertingIds()))
                            .flatMap(v -> users.markDirty(publicKey))
                            .replaceWith(generated);
                });
            });
        });
    }

    /** True for the value of a rarity of the items: "common", "uncommon", "rare", "epic" or "legendary". */
    public static boolean isRarity(String value) {
        return value != null && java.util.Arrays.stream(ItemCatalog.Rarity.values())
                .anyMatch(r -> r.value().equals(value));
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
     * never touch the database, except those an interrupted synchronization may have written (see
     * {@link ItemList#insertingIds}), which are deleted from it first. Otherwise the database is only called when
     * database items are sold, or when the user has no cached session to credit (the pending items' value is then
     * added in the same transaction).
     */
    public Uni<SellResult> sell(String publicKey, String privateKey, List<Integer> ids) {
        return lock.withLock(publicKey, () -> sellLocked(publicKey, privateKey, ids).flatMap(result ->
                result.status() != SellResult.Status.OK ? Uni.createFrom().item(result)
                        : quests.addLocked(publicKey, Map.of(Metric.SALES, result.soldIds().size()))
                                .replaceWith(result)));
    }

    private Uni<SellResult> sellLocked(String publicKey, String privateKey, List<Integer> ids) {
        return users.authenticate(publicKey, privateKey).flatMap(user -> {
            if (user == null) {
                return Uni.createFrom().item(SellResult.of(SellResult.Status.UNAUTHORIZED));
            }
            return list(publicKey).flatMap(list -> equipped.ids(publicKey).flatMap(worn -> {
                Map<Integer, ItemInfo> byId = new HashMap<>();
                // an equipped item is not in the inventory: it must be taken off before it can be sold
                inventory(list, worn).forEach(i -> byId.put(i.id(), i));
                if (!byId.keySet().containsAll(ids)) {
                    return Uni.createFrom().item(SellResult.of(SellResult.Status.NOT_FOUND));
                }
                Set<Integer> pending = list.pendingIds();
                List<Integer> dbIds = ids.stream().filter(id -> !pending.contains(id)).toList();
                int redisTotal = ids.stream().filter(pending::contains).mapToInt(id -> price(byId.get(id))).sum();
                List<Integer> maybeWritten = ids.stream().filter(list.insertingIds()::contains).toList();
                // deleted before the user is credited: if the sale fails afterwards, the items are still pending
                // and the next synchronization writes them again
                Uni<Void> cleanup = maybeWritten.isEmpty() ? Uni.createFrom().voidItem()
                        : repository.deleteByIds(user.id(), maybeWritten);
                return cleanup.flatMap(v -> dbIds.isEmpty()
                        ? sellRedisOnly(publicKey, privateKey, ids, redisTotal)
                        : sellWithDatabase(publicKey, privateKey, ids, dbIds, redisTotal));
            }));
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
     * keeping their id). insertingIds are those among them a synchronization started to insert without confirming
     * it: the database may or may not have them.
     */
    public record ItemList(
            @JsonProperty("userFound") boolean userFound,
            @JsonProperty("items") List<ItemInfo> items,
            @JsonProperty("pendingIds") Set<Integer> pendingIds,
            @JsonProperty("insertingIds") Set<Integer> insertingIds) {

        public ItemList {
            pendingIds = pendingIds == null ? Set.of() : Set.copyOf(pendingIds);
            // absent from the lists cached before this field existed
            insertingIds = insertingIds == null ? Set.of() : Set.copyOf(insertingIds);
        }

        /** A list with no insertion under way. */
        public ItemList(boolean userFound, List<ItemInfo> items, Set<Integer> pendingIds) {
            this(userFound, items, pendingIds, Set.of());
        }

        /** A list with nothing waiting for the database, as read from it. */
        public ItemList(boolean userFound, List<ItemInfo> items) {
            this(userFound, items, Set.of());
        }

        public static ItemList unknownUser() {
            return new ItemList(false, List.of());
        }

        /** A copy without these items. */
        public ItemList without(Collection<Integer> ids) {
            Set<Integer> removed = new HashSet<>(ids);
            return new ItemList(userFound, items.stream().filter(i -> !removed.contains(i.id())).toList(),
                    minus(pendingIds, removed), minus(insertingIds, removed));
        }

        private static Set<Integer> minus(Set<Integer> ids, Set<Integer> removed) {
            return ids.stream().filter(id -> !removed.contains(id)).collect(Collectors.toSet());
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

        /**
         * Inserts pending items for the user, with the ids they already have. Those the user already has in the
         * database are left alone, so an interrupted synchronization can be run again.
         */
        Uni<Void> insertAll(int userId, List<ItemInfo> items);

        Uni<Void> deleteByIds(int userId, List<Integer> ids);
    }

    /** Implemented by the character domain. */
    public interface Equipped {
        /** Ids of the items equipped on the characters of the user; empty when the public key is unknown. */
        Uni<Set<Integer>> ids(String publicKey);
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
