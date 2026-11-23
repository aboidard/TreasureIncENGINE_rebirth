package org.bogomips.treasureInc.engine.expedition;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bogomips.treasureInc.engine.character.CharacterService;
import org.bogomips.treasureInc.engine.character.CharacterService.XpGain;
import org.bogomips.treasureInc.engine.events.EventService;
import org.bogomips.treasureInc.engine.events.GameEvent;
import org.bogomips.treasureInc.engine.user.UserInfo;
import org.bogomips.treasureInc.engine.item.Equipment;
import org.bogomips.treasureInc.engine.item.Equipment.Skill;
import org.bogomips.treasureInc.engine.item.ItemInfo;
import org.bogomips.treasureInc.engine.item.ItemService;
import org.bogomips.treasureInc.engine.quest.QuestCatalog.Metric;
import org.bogomips.treasureInc.engine.quest.QuestTracker;
import org.bogomips.treasureInc.engine.user.UserService;
import org.bogomips.treasureInc.engine.user.UserService.SpendResult;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Digging the cases of a location, sending an expedition and opening its chest. Each runs under the user's lock.
 * What happens to the character on the way (obstacles, fights) is decided when the expedition is sent, see
 * {@link ExpeditionRun}.
 */
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
    private final ExpeditionConfig.Obstacles obstacles;
    private final RandomGenerator random;
    private final QuestTracker quests;
    /** Counts the expeditions sent to the events; null in the tests that send none. */
    private EventService events;

    @Inject
    ExpeditionService(Store store, UserService users, ItemService items, UserService.Lock lock, ExpeditionRules rules,
                      CharacterService characters, ExpeditionConfig config, QuestTracker quests,
                      EventService events) {
        this(store, users, items, lock, rules, characters, config.obstacles(), RandomGenerator.getDefault(), quests);
        this.events = events;
    }

    /** The events whose expeditions this service sends (the tests). */
    ExpeditionService withEvents(EventService events) {
        this.events = events;
        return this;
    }

    /** An expedition service that counts nothing for the quests. */
    ExpeditionService(Store store, UserService users, ItemService items, UserService.Lock lock, ExpeditionRules rules,
                      CharacterService characters, ExpeditionConfig.Obstacles obstacles, RandomGenerator random) {
        this(store, users, items, lock, rules, characters, obstacles, random, QuestTracker.NONE);
    }

    ExpeditionService(Store store, UserService users, ItemService items, UserService.Lock lock, ExpeditionRules rules,
                      CharacterService characters, ExpeditionConfig.Obstacles obstacles, RandomGenerator random,
                      QuestTracker quests) {
        this.quests = quests;
        if (obstacles.spots() < 0 || obstacles.barricadeChance() < 0 || obstacles.monsterChance() < 0
                || obstacles.barricadeChance() + obstacles.monsterChance() > 100) {
            throw new IllegalArgumentException("engine.expeditions.obstacles: the chances of a barricade and of a "
                    + "monster must be positive and at most 100 together");
        }
        this.obstacles = obstacles;
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
                    .flatMap(v -> quests.addLocked(publicKey, Map.of(Metric.DIGS, 1)))
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
        return startWith(publicKey, privateKey, character,
                () -> send(publicKey, privateKey, location, difficulty, caseId, character));
    }

    /**
     * Sends an expedition to an event, for free: its floors, its level and its rewards are those of the event when it
     * is sent. Refused ({@link StartResult#limitReached}) once the player sent as many expeditions there today as the
     * event allows.
     */
    Uni<StartResult> startEvent(String publicKey, String privateKey, GameEvent event, int character) {
        return startWith(publicKey, privateKey, character, () -> sendToEvent(publicKey, privateKey, event, character));
    }

    private Uni<StartResult> sendToEvent(String publicKey, String privateKey, GameEvent event, int character) {
        return users.openSession(publicKey, privateKey).flatMap(user -> {
            if (user == null) {
                return Uni.createFrom().item(StartResult.refused(SpendResult.Status.UNAUTHORIZED, 0, false, false));
            }
            return events.runsToday(publicKey, event.code()).flatMap(runs -> {
                if (event.maxPerDay() > 0 && runs >= event.maxPerDay()) {
                    return Uni.createFrom().item(new StartResult(SpendResult.Status.OK, null, user.money(), false,
                            false, null, true));
                }
                int floors = event.floors();
                return characters.fighterLocked(publicKey, character).flatMap(fighter -> {
                    ExpeditionRun run = ExpeditionRun.plan(fighter, event.level(), floors, obstacles, random);
                    Expedition expedition = new Expedition(UUID.randomUUID().toString(), event.location(), floors,
                            NO_RARITY, event.rewardItems(), Instant.now().toString(), character,
                            ExpeditionRules.share(rules.xpAtLevel(event.level(), floors, NO_RARITY),
                                    run.floorsCompleted(), floors),
                            run.floorsCompleted(), run.bonusItems(), run.monstersFought(), event.level(),
                            event.rewardGems(), event.rewardMoney());
                    return store.put(publicKey, expedition)
                            .flatMap(v -> events.countRun(publicKey, event.code()))
                            .replaceWith(new StartResult(SpendResult.Status.OK, expedition, user.money(), false,
                                    false, run, false));
                });
            });
        });
    }

    private Uni<StartResult> startWith(String publicKey, String privateKey, int character,
                                       Supplier<Uni<StartResult>> send) {
        return lock.withLock(publicKey, () -> store.list(publicKey).flatMap(underWay -> {
            if (busyCharacters(underWay).containsKey(character)) {
                // the credentials are still checked first, so a busy character tells nothing to a stranger
                return users.openSession(publicKey, privateKey).map(user -> user == null
                        ? StartResult.refused(SpendResult.Status.UNAUTHORIZED, 0, false, false)
                        : StartResult.refused(SpendResult.Status.OK, user.money(), true, false));
            }
            return characters.ownsLocked(publicKey, character).flatMap(owned -> owned
                    ? send.get()
                    // same as above: only the user learns which characters it has
                    : users.openSession(publicKey, privateKey).map(user -> user == null
                            ? StartResult.refused(SpendResult.Status.UNAUTHORIZED, 0, false, false)
                            : StartResult.refused(SpendResult.Status.OK, user.money(), false, true)));
        }));
    }

    private Uni<StartResult> send(String publicKey, String privateKey, String location, int difficulty,
                                  Integer caseId, int character) {
        Uni<Integer> dug = caseId == null ? Uni.createFrom().nullItem() : store.dugRarity(publicKey, location, caseId);
        return dug.flatMap(known -> {
            int rarity = known == null ? NO_RARITY : known;
            return users.spendLocked(publicKey, privateKey, rules.cost(location, difficulty)).flatMap(spent -> {
                if (spent.status() != SpendResult.Status.OK) {
                    return Uni.createFrom().item(StartResult.refused(spent.status(), spent.money(), false, false));
                }
                // the character fights as it is now: its equipment cannot change before it is back
                return characters.fighterLocked(publicKey, character).flatMap(fighter -> {
                    ExpeditionRun run = ExpeditionRun.plan(fighter, rules.level(location), difficulty, obstacles,
                            random);
                    Expedition expedition = new Expedition(UUID.randomUUID().toString(), location, difficulty,
                            rarity, rules.itemCount(location, difficulty, rarity), Instant.now().toString(),
                            character,
                            ExpeditionRules.share(rules.xp(location, difficulty, rarity), run.floorsCompleted(),
                                    difficulty),
                            run.floorsCompleted(), run.bonusItems(), run.monstersFought());
                    return store.put(publicKey, expedition)
                            .flatMap(v -> store.clearDug(publicKey, location))
                            .replaceWith(new StartResult(SpendResult.Status.OK, expedition, spent.money(), false,
                                    false, run, false));
                });
            });
        });
    }

    /**
     * Generates the items of the expedition's chest; the expedition is removed, so a chest only opens once and its
     * character is available again. Each "luck_charm" item worn by the character may add one item to the chest.
     * The character earns the experience points of the expedition.
     * An expedition whose character died on the way only brings back the share of the floors it finished, of the
     * items as of the experience: nothing at all when it died on the first floor.
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
                int found = expedition.itemsFound();
                // a character that comes back with nothing has no chest for its luck charms to fill
                Uni<Integer> lucky = found > 0 ? luckyItems(publicKey, expedition) : Uni.createFrom().item(0);
                return lucky.flatMap(bonus -> items.generateLocked(publicKey, privateKey, found + bonus,
                                lootOf(expedition)))
                        .flatMap(generated -> experience(publicKey, expedition)
                                .flatMap(xp -> reward(publicKey, expedition, user)
                                        .flatMap(rewarded -> store.remove(publicKey, expeditionId)
                                                .flatMap(v -> quests.addLocked(publicKey,
                                                        questAmounts(expedition, generated)))
                                                .replaceWith(new OpenResult(OpenResult.Status.OK, generated, xp,
                                                        expedition, expedition.gemsFound(), expedition.moneyFound(),
                                                        rewarded)))));
            });
        }));
    }

    /** What an expedition counts for in the quests, once its chest is opened. */
    private static Map<Metric, Integer> questAmounts(Expedition expedition, List<ItemInfo> found) {
        Map<Metric, Integer> amounts = new EnumMap<>(Metric.class);
        amounts.put(Metric.EXPEDITIONS, 1);
        amounts.put(Metric.FLOORS, expedition.completed());
        amounts.put(Metric.CLEARS, expedition.completed() == expedition.difficulty() ? 1 : 0);
        amounts.put(Metric.MONSTERS, expedition.monsters() == null ? 0 : expedition.monsters());
        amounts.put(Metric.ITEMS, found.size());
        amounts.put(Metric.EQUIPMENT, (int) found.stream().filter(ItemInfo::equipable).count());
        return amounts;
    }

    /**
     * The rarity chances of the expedition's location: for an event, those of the location of the story of the same
     * level; the usual ones for a location that no longer exists.
     */
    private List<Integer> lootOf(Expedition expedition) {
        if (expedition.level() != null) {
            List<Integer> loot = rules.lootAtLevel(expedition.level());
            return loot == null ? STANDARD_LOOT : loot;
        }
        String location = rules.locationKey(expedition.location());
        return location == null ? STANDARD_LOOT : rules.loot(location);
    }

    /** Credits the gems and the money the expedition brings back (an event's); the user's balance afterwards. */
    private Uni<UserInfo> reward(String publicKey, Expedition expedition, UserInfo user) {
        int gems = expedition.gemsFound();
        int money = expedition.moneyFound();
        return gems == 0 && money == 0 ? Uni.createFrom().item(user) : users.credit(publicKey, money, gems, true);
    }

    /** The rarity chances of the items found outside an expedition (see ItemCatalog.Rarity#fromRoll(int)). */
    static final List<Integer> STANDARD_LOOT = List.of(650, 200, 140, 9, 1);

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
     * itemCount is the content of the chest of an expedition that goes to its end. floorsCompleted says how many
     * floors the character finished before it died, or all of them (null for an expedition sent before the fights:
     * all of them), and bonusItems what its skills add to the chest. monsters is how many monsters it fought
     * (null for an expedition sent before the quests): the quests count them when the chest is opened.
     */
    record Expedition(
            @JsonProperty("id") String id,
            @JsonProperty("location") String location,
            @JsonProperty("difficulty") int difficulty,
            @JsonProperty("rarity") int rarity,
            @JsonProperty("itemCount") int itemCount,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("character") Integer character,
            @JsonProperty("xp") Integer xp,
            @JsonProperty("floorsCompleted") Integer floorsCompleted,
            @JsonProperty("bonusItems") Integer bonusItems,
            @JsonProperty("monsters") Integer monsters,
            @JsonProperty("level") Integer level,
            @JsonProperty("gems") Integer gems,
            @JsonProperty("money") Integer money) {

        /**
         * An expedition to a location of the story. level, gems and money are only set for an event: its level
         * (a location of the story has its own), and what its chest gives besides the items.
         */
        Expedition(String id, String location, int difficulty, int rarity, int itemCount, String createdAt,
                   Integer character, Integer xp, Integer floorsCompleted, Integer bonusItems, Integer monsters) {
            this(id, location, difficulty, rarity, itemCount, createdAt, character, xp, floorsCompleted, bonusItems,
                    monsters, null, null, null);
        }

        /** The gems the character brings back: the share of the floors it finished. */
        int gemsFound() {
            return gems == null ? 0 : ExpeditionRules.share(gems, completed(), difficulty);
        }

        /** The money the character brings back, likewise. */
        int moneyFound() {
            return money == null ? 0 : ExpeditionRules.share(money, completed(), difficulty);
        }

        /** How many floors the character finished. */
        int completed() {
            return floorsCompleted == null ? difficulty : Math.min(difficulty, Math.max(0, floorsCompleted));
        }

        /** The items the character brings back, before its luck charms. */
        int itemsFound() {
            return ExpeditionRules.share(itemCount, completed(), difficulty) + (bonusItems == null ? 0 : bonusItems);
        }
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
     * run is what happens to the character on the way; null unless the expedition was sent.
     */
    record StartResult(SpendResult.Status status, Expedition expedition, int money, boolean characterBusy,
                       boolean unknownCharacter, ExpeditionRun run, boolean limitReached) {

        static StartResult refused(SpendResult.Status status, int money, boolean characterBusy,
                                   boolean unknownCharacter) {
            return new StartResult(status, null, money, characterBusy, unknownCharacter, null, false);
        }
    }

    /**
     * xp is what the character of the expedition earned; null when nobody led it.
     * expedition is the expedition whose chest was opened; null unless OK.
     */
    record OpenResult(Status status, List<ItemInfo> items, XpGain xp, Expedition expedition, int gems, int money,
                      UserInfo balance) {

        enum Status { OK, UNAUTHORIZED, NOT_FOUND }

        static OpenResult of(Status status) {
            return new OpenResult(status, List.of(), null, null, 0, 0, null);
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
