package org.bogomips.treasureInc.engine.expedition;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bogomips.treasureInc.engine.character.CharacterService;
import org.bogomips.treasureInc.engine.character.CharacterService.XpGain;
import org.bogomips.treasureInc.engine.item.Equipment;
import org.bogomips.treasureInc.engine.item.Equipment.Skill;
import org.bogomips.treasureInc.engine.item.ItemInfo;
import org.bogomips.treasureInc.engine.item.ItemService;
import org.bogomips.treasureInc.engine.user.UserService;
import org.bogomips.treasureInc.engine.user.UserService.SpendResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
    private final CharacterService characters;
    private final RandomGenerator random;

    @Inject
    ExpeditionService(Store store, UserService users, ItemService items, UserService.Lock lock, ExpeditionRules rules,
                      CharacterService characters) {
        this(store, users, items, lock, rules, characters, RandomGenerator.getDefault());
    }

    ExpeditionService(Store store, UserService users, ItemService items, UserService.Lock lock, ExpeditionRules rules,
                      CharacterService characters, RandomGenerator random) {
        this.characters = characters;
        this.random = random;
        this.store = store;
        this.users = users;
        this.items = items;
        this.lock = lock;
        this.rules = rules;
    }

    /**
     * Reveals a case: the first dig pays {@code cost} and rolls the rarity. Digging it again answers the same rarity
     * for free until an expedition is sent to this location, so a player cannot reroll until a case is good enough.
     * Nothing is dug when every character is on an expedition: there is nobody left to send.
     */
    Uni<DigResult> dig(String publicKey, String privateKey, String location, int caseId, int cost) {
        return lock.withLock(publicKey, () -> users.openSession(publicKey, privateKey).flatMap(user -> {
            if (user == null) {
                return Uni.createFrom().item(DigResult.of(SpendResult.Status.UNAUTHORIZED, NO_RARITY, 0));
            }
            return store.list(publicKey).flatMap(underWay -> characters.countLocked(publicKey).flatMap(owned -> {
                if (busyCharacters(underWay).size() >= owned) {
                    return Uni.createFrom().item(new DigResult(SpendResult.Status.OK, NO_RARITY, user.money(), true));
                }
                return store.dugRarity(publicKey, location, caseId).flatMap(known -> known != null
                        ? Uni.createFrom().item(DigResult.of(SpendResult.Status.OK, known, user.money()))
                        : digNewCase(publicKey, privateKey, location, caseId, cost));
            }));
        }));
    }

    private Uni<DigResult> digNewCase(String publicKey, String privateKey, String location, int caseId, int cost) {
        return users.spendLocked(publicKey, privateKey, cost).flatMap(spent -> {
            if (spent.status() != SpendResult.Status.OK) {
                return Uni.createFrom().item(DigResult.of(spent.status(), NO_RARITY, spent.money()));
            }
            int rarity = ExpeditionRules.rarityFromRoll(random.nextInt(RARITY_ROLLS) + 1);
            return store.putDug(publicKey, location, caseId, rarity)
                    .replaceWith(DigResult.of(SpendResult.Status.OK, rarity, spent.money()));
        });
    }

    /**
     * Pays for an expedition and stores it; the dug cases of the location are forgotten.
     * The character leading it is busy until the chest is opened: it cannot lead another expedition meanwhile.
     *
     * @param caseId    the selected case, or null; a case that was not dug counts as no case
     * @param character number of the character leading the expedition: one the user has
     */
    Uni<StartResult> start(String publicKey, String privateKey, String location, int difficulty, Integer caseId,
                           int character) {
        return lock.withLock(publicKey, () -> store.list(publicKey).flatMap(underWay -> {
            if (busyCharacters(underWay).containsKey(character)) {
                // the credentials are still checked first, so a busy character tells nothing to a stranger
                return users.openSession(publicKey, privateKey).map(user -> user == null
                        ? new StartResult(SpendResult.Status.UNAUTHORIZED, null, 0, false, false)
                        : new StartResult(SpendResult.Status.OK, null, user.money(), true, false));
            }
            return characters.ownsLocked(publicKey, character).flatMap(owned -> owned
                    ? send(publicKey, privateKey, location, difficulty, caseId, character)
                    // same as above: only the user learns which characters it has
                    : users.openSession(publicKey, privateKey).map(user -> user == null
                            ? new StartResult(SpendResult.Status.UNAUTHORIZED, null, 0, false, false)
                            : new StartResult(SpendResult.Status.OK, null, user.money(), false, true)));
        }));
    }

    private Uni<StartResult> send(String publicKey, String privateKey, String location, int difficulty,
                                  Integer caseId, int character) {
        Uni<Integer> dug = caseId == null ? Uni.createFrom().nullItem() : store.dugRarity(publicKey, location, caseId);
        return dug.flatMap(known -> {
            int rarity = known == null ? NO_RARITY : known;
            return users.spendLocked(publicKey, privateKey, rules.cost(location, difficulty)).flatMap(spent -> {
                if (spent.status() != SpendResult.Status.OK) {
                    return Uni.createFrom().item(new StartResult(spent.status(), null, spent.money(), false, false));
                }
                Expedition expedition = new Expedition(UUID.randomUUID().toString(), location, difficulty, rarity,
                        rules.itemCount(location, difficulty, rarity), Instant.now().toString(), character,
                        rules.xp(location, difficulty, rarity));
                return store.put(publicKey, expedition)
                        .flatMap(v -> store.clearDug(publicKey, location))
                        .replaceWith(new StartResult(SpendResult.Status.OK, expedition, spent.money(), false, false));
            });
        });
    }

    /**
     * Generates the items of the expedition's chest; the expedition is removed, so a chest only opens once and its
     * character is available again. Each "luck_charm" item worn by the character may add one item to the chest.
     * The character earns the experience points of the expedition.
     */
    Uni<OpenResult> open(String publicKey, String privateKey, String expeditionId) {
        return lock.withLock(publicKey, () -> users.openSession(publicKey, privateKey).flatMap(user -> {
            if (user == null) {
                return Uni.createFrom().item(OpenResult.of(OpenResult.Status.UNAUTHORIZED));
            }
            return store.get(publicKey, expeditionId).flatMap(expedition -> {
                if (expedition == null) {
                    return Uni.createFrom().item(OpenResult.of(OpenResult.Status.NOT_FOUND));
                }
                return luckyItems(publicKey, expedition).flatMap(bonus ->
                        items.generateLocked(publicKey, privateKey, expedition.itemCount() + bonus))
                        .flatMap(generated -> experience(publicKey, expedition)
                                .flatMap(xp -> store.remove(publicKey, expeditionId)
                                        .replaceWith(new OpenResult(OpenResult.Status.OK, generated, xp))));
            });
        }));
    }

    /** How many items the luck charms of the expedition's character add to its chest. */
    private Uni<Integer> luckyItems(String publicKey, Expedition expedition) {
        if (expedition.character() == null) {
            return Uni.createFrom().item(0);
        }
        return characters.equippedLocked(publicKey, expedition.character()).map(worn -> (int) worn.stream()
                .filter(item -> Equipment.hasSkill(item.stats(), Skill.LUCK_CHARM))
                .filter(item -> random.nextInt(100) < Skill.LUCK_CHARM_CHANCE)
                .count());
    }

    /** What the expedition brings to its character; null when nobody led it, or a character the user lost. */
    private Uni<XpGain> experience(String publicKey, Expedition expedition) {
        if (expedition.character() == null) {
            return Uni.createFrom().nullItem();
        }
        // an expedition sent before the levels existed has no points of its own
        int xp = expedition.xp() != null ? expedition.xp()
                : rules.xp(expedition.location(), expedition.difficulty(), expedition.rarity());
        return characters.gainXpLocked(publicKey, expedition.character(), xp);
    }

    /** The id of the expedition under way of each busy character, by character number. */
    static Map<Integer, String> busyCharacters(List<Expedition> underWay) {
        Map<Integer, String> busy = new TreeMap<>();
        underWay.stream().filter(e -> e.character() != null).forEach(e -> busy.put(e.character(), e.id()));
        return busy;
    }

    // ---- values

    /**
     * An expedition on its way: it only lives in Redis, until its chest is opened.
     * character is the number of the character leading it (null for an expedition sent before characters existed).
     * xp is what this character earns when the chest is opened (null for an expedition sent before the levels).
     */
    record Expedition(
            @JsonProperty("id") String id,
            @JsonProperty("location") String location,
            @JsonProperty("difficulty") int difficulty,
            @JsonProperty("rarity") int rarity,
            @JsonProperty("itemCount") int itemCount,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("character") Integer character,
            @JsonProperty("xp") Integer xp) {
    }

    /**
     * Money is the balance afterwards (unchanged unless OK or when the case was already dug).
     * noCharacter: nothing was dug because every character is on an expedition.
     */
    record DigResult(SpendResult.Status status, int rarity, int money, boolean noCharacter) {

        static DigResult of(SpendResult.Status status, int rarity, int money) {
            return new DigResult(status, rarity, money, false);
        }
    }

    /**
     * Money is the balance afterwards (unchanged unless OK); the expedition is null unless it was sent.
     * characterBusy: nothing was sent because the character is already on an expedition.
     * unknownCharacter: nothing was sent because the user does not have this character.
     */
    record StartResult(SpendResult.Status status, Expedition expedition, int money, boolean characterBusy,
                       boolean unknownCharacter) {
    }

    /** xp is what the character of the expedition earned; null when nobody led it. */
    record OpenResult(Status status, List<ItemInfo> items, XpGain xp) {

        enum Status { OK, UNAUTHORIZED, NOT_FOUND }

        static OpenResult of(Status status) {
            return new OpenResult(status, List.of(), null);
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

        /** Every expedition of the user whose chest is not opened yet. */
        Uni<List<Expedition>> list(String publicKey);

        Uni<Void> put(String publicKey, Expedition expedition);

        Uni<Void> remove(String publicKey, String expeditionId);
    }
}
