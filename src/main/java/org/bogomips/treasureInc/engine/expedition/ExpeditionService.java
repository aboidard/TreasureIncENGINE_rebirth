package org.bogomips.treasureInc.engine.expedition;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.item.ItemInfo;
import org.bogomips.treasureInc.engine.item.ItemService;
import org.bogomips.treasureInc.engine.user.UserService;
import org.bogomips.treasureInc.engine.user.UserService.SpendResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Digging the cases of a location, sending an expedition and opening its chest. Each runs under the user's lock. */
@ApplicationScoped
class ExpeditionService {

    private static final int RARITY_ROLLS = 1000;
    /** Rarity of an expedition sent without a dug case. */
    private static final int NO_RARITY = 0;

    private final Store store;
    private final UserService users;
    private final ItemService items;
    private final UserService.Lock lock;
    private final ExpeditionRules rules;
    private final RandomGenerator random = RandomGenerator.getDefault();

    ExpeditionService(Store store, UserService users, ItemService items, UserService.Lock lock, ExpeditionRules rules) {
        this.store = store;
        this.users = users;
        this.items = items;
        this.lock = lock;
        this.rules = rules;
    }

    /**
     * Reveals a case: the first dig pays {@code cost} and rolls the rarity. Digging it again answers the same rarity
     * for free until an expedition is sent to this location, so a player cannot reroll until a case is good enough.
     */
    Uni<DigResult> dig(String publicKey, String privateKey, String location, int caseId, int cost) {
        return lock.withLock(publicKey, () -> users.openSession(publicKey, privateKey).flatMap(user -> {
            if (user == null) {
                return Uni.createFrom().item(new DigResult(SpendResult.Status.UNAUTHORIZED, NO_RARITY, 0));
            }
            return store.dugRarity(publicKey, location, caseId).flatMap(known -> known != null
                    ? Uni.createFrom().item(new DigResult(SpendResult.Status.OK, known, user.money()))
                    : digNewCase(publicKey, privateKey, location, caseId, cost));
        }));
    }

    private Uni<DigResult> digNewCase(String publicKey, String privateKey, String location, int caseId, int cost) {
        return users.spendLocked(publicKey, privateKey, cost).flatMap(spent -> {
            if (spent.status() != SpendResult.Status.OK) {
                return Uni.createFrom().item(new DigResult(spent.status(), NO_RARITY, spent.money()));
            }
            int rarity = ExpeditionRules.rarityFromRoll(random.nextInt(RARITY_ROLLS) + 1);
            return store.putDug(publicKey, location, caseId, rarity)
                    .replaceWith(new DigResult(SpendResult.Status.OK, rarity, spent.money()));
        });
    }

    /**
     * Pays for an expedition and stores it; the dug cases of the location are forgotten.
     *
     * @param caseId the selected case, or null; a case that was not dug counts as no case
     */
    Uni<StartResult> start(String publicKey, String privateKey, String location, int difficulty, Integer caseId) {
        return lock.withLock(publicKey, () -> {
            Uni<Integer> dug = caseId == null ? Uni.createFrom().nullItem() : store.dugRarity(publicKey, location, caseId);
            return dug.flatMap(known -> {
                int rarity = known == null ? NO_RARITY : known;
                return users.spendLocked(publicKey, privateKey, rules.cost(location, difficulty)).flatMap(spent -> {
                    if (spent.status() != SpendResult.Status.OK) {
                        return Uni.createFrom().item(new StartResult(spent.status(), null, spent.money()));
                    }
                    Expedition expedition = new Expedition(UUID.randomUUID().toString(), location, difficulty, rarity,
                            rules.itemCount(location, difficulty, rarity), Instant.now().toString());
                    return store.put(publicKey, expedition)
                            .flatMap(v -> store.clearDug(publicKey, location))
                            .replaceWith(new StartResult(SpendResult.Status.OK, expedition, spent.money()));
                });
            });
        });
    }

    /** Generates the items of the expedition's chest; the expedition is removed, so a chest only opens once. */
    Uni<OpenResult> open(String publicKey, String privateKey, String expeditionId) {
        return lock.withLock(publicKey, () -> users.openSession(publicKey, privateKey).flatMap(user -> {
            if (user == null) {
                return Uni.createFrom().item(OpenResult.of(OpenResult.Status.UNAUTHORIZED));
            }
            return store.get(publicKey, expeditionId).flatMap(expedition -> {
                if (expedition == null) {
                    return Uni.createFrom().item(OpenResult.of(OpenResult.Status.NOT_FOUND));
                }
                return items.generateLocked(publicKey, privateKey, expedition.itemCount()).flatMap(generated ->
                        store.remove(publicKey, expeditionId)
                                .replaceWith(new OpenResult(OpenResult.Status.OK, generated)));
            });
        }));
    }

    // ---- values

    /** An expedition on its way: it only lives in Redis, until its chest is opened. */
    record Expedition(
            @JsonProperty("id") String id,
            @JsonProperty("location") String location,
            @JsonProperty("difficulty") int difficulty,
            @JsonProperty("rarity") int rarity,
            @JsonProperty("itemCount") int itemCount,
            @JsonProperty("createdAt") String createdAt) {
    }

    /** Money is the balance afterwards (unchanged unless OK or when the case was already dug). */
    record DigResult(SpendResult.Status status, int rarity, int money) {
    }

    /** Money is the balance afterwards (unchanged unless OK); the expedition is null unless OK. */
    record StartResult(SpendResult.Status status, Expedition expedition, int money) {
    }

    record OpenResult(Status status, List<ItemInfo> items) {

        enum Status { OK, UNAUTHORIZED, NOT_FOUND }

        static OpenResult of(Status status) {
            return new OpenResult(status, List.of());
        }
    }

    // ---- port, implemented by the Redis adapter

    interface Store {
        /** Null when the case was not dug. */
        Uni<Integer> dugRarity(String publicKey, String location, int caseId);

        Uni<Void> putDug(String publicKey, String location, int caseId, int rarity);

        /** Forgets every dug case of the location. */
        Uni<Void> clearDug(String publicKey, String location);

        /** Null when the user has no such expedition. */
        Uni<Expedition> get(String publicKey, String expeditionId);

        Uni<Void> put(String publicKey, Expedition expedition);

        Uni<Void> remove(String publicKey, String expeditionId);
    }
}
