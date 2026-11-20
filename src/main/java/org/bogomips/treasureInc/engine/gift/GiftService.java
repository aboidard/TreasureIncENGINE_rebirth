package org.bogomips.treasureInc.engine.gift;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bogomips.treasureInc.engine.item.ItemInfo;
import org.bogomips.treasureInc.engine.item.ItemService;
import org.bogomips.treasureInc.engine.user.UserService;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The gift box of the players: gifts sent by the administrators (through Maestro) or given by the game, each with a
 * title and a reward (money, gems, items). A gift waits in the box until the player takes it, once; a gift may
 * expire. Unlike the rest of the game state, the gifts live in the database: they are sent to players who may not
 * have played for weeks, so they never are in Redis.
 */
@ApplicationScoped
public class GiftService {

    private final Repository repository;
    private final UserService users;
    private final ItemService items;
    private final UserService.Lock lock;
    private final int listLimit;
    private final Clock clock;

    @Inject
    public GiftService(Repository repository, UserService users, ItemService items, UserService.Lock lock,
                       @ConfigProperty(name = "engine.gifts.list-limit", defaultValue = "100") int listLimit) {
        this(repository, users, items, lock, listLimit, Clock.systemUTC());
    }

    public GiftService(Repository repository, UserService users, ItemService items, UserService.Lock lock,
                       int listLimit, Clock clock) {
        if (listLimit < 1) {
            throw new IllegalArgumentException("engine.gifts.list-limit must be positive");
        }
        this.repository = repository;
        this.users = users;
        this.items = items;
        this.lock = lock;
        this.listLimit = listLimit;
        this.clock = clock;
    }

    /** The gifts waiting in the box of the user, the latest first; null on bad credentials. */
    public Uni<Pending> list(String publicKey, String privateKey) {
        return users.authenticate(publicKey, privateKey).flatMap(user -> user == null
                ? Uni.createFrom().<Pending>nullItem()
                : repository.pending(user.id(), clock.instant(), listLimit));
    }

    /**
     * Takes a gift: its money and gems are credited and its items generated. The gift is marked as taken in the
     * database first, so a failure afterwards cannot give it twice.
     */
    public Uni<ClaimResult> claim(String publicKey, String privateKey, int giftId) {
        return lock.withLock(publicKey, () -> users.openSession(publicKey, privateKey).flatMap(user -> {
            if (user == null) {
                return Uni.createFrom().item(ClaimResult.refused(ClaimOutcome.Status.UNAUTHORIZED));
            }
            return repository.claim(user.id(), giftId, clock.instant()).flatMap(outcome -> {
                if (outcome.status() != ClaimOutcome.Status.OK) {
                    return Uni.createFrom().item(ClaimResult.refused(outcome.status()));
                }
                GiftInfo gift = outcome.gift();
                Reward reward = gift.reward();
                return users.credit(publicKey, reward.money(), reward.gems(), true)
                        .flatMap(paid -> items.generateLocked(publicKey, privateKey, reward.items(), reward.rarity())
                                .flatMap(given -> repository.pending(user.id(), clock.instant(), listLimit)
                                        .map(left -> new ClaimResult(ClaimOutcome.Status.OK, gift, paid.money(),
                                                paid.gems(), given, left))));
            });
        }));
    }

    /**
     * Puts a gift in the box of every player matching the target: {@code expiresIn} is null for a gift that never
     * expires. The values must have been checked (see {@link Reward#isValid}).
     */
    public Uni<SendReport> send(Target target, String title, Reward reward, Duration expiresIn) {
        String batch = UUID.randomUUID().toString();
        return repository.send(target, title, reward, expiresIn, batch, clock.instant())
                .map(sent -> new SendReport(batch, sent));
    }

    /** {"count", "gifts": [{"id", "title", "reward": {"money", "gems", "items", "rarity"}, "createdAt", "expiresAt"}]} */
    public static Map<String, Object> toMap(Pending pending) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", pending.count());
        m.put("gifts", pending.gifts().stream().map(GiftService::toMap).toList());
        return m;
    }

    private static Map<String, Object> toMap(GiftInfo gift) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", gift.id());
        m.put("title", gift.title());
        m.put("reward", gift.reward().toMap());
        m.put("createdAt", gift.createdAt());
        m.put("expiresAt", gift.expiresAt());
        return m;
    }

    // ---- values

    /**
     * What a gift gives.
     *
     * @param items  how many items are generated when the gift is taken
     * @param rarity the rarity of these items ("common"... "legendary"); null when each one is drawn as in a chest
     */
    public record Reward(int money, int gems, int items, String rarity) {

        /** The most money or gems a gift can give, so that no balance can overflow. */
        public static final int MAX_AMOUNT = 100_000_000;
        /** The most items a gift can give. */
        public static final int MAX_ITEMS = 100;

        /** Something to give, nothing negative or too big, a known rarity, and a rarity only with items. */
        public boolean isValid() {
            return money >= 0 && gems >= 0 && items >= 0 && money + gems + items > 0
                    && money <= MAX_AMOUNT && gems <= MAX_AMOUNT && items <= MAX_ITEMS
                    && (rarity == null || (items > 0 && ItemService.isRarity(rarity)));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("money", money);
            m.put("gems", gems);
            m.put("items", items);
            m.put("rarity", rarity);
            return m;
        }
    }

    /**
     * Who receives a gift: every player matching all the criteria given; null criteria are ignored, so with none at
     * all every player receives it.
     *
     * @param activeDays   players who played within the last days
     * @param inactiveDays players who have not played for these days (to welcome them back)
     * @param createdDays  players who started within the last days
     * @param publicKeys   these players only
     */
    public record Target(Integer activeDays, Integer inactiveDays, Integer createdDays, List<String> publicKeys) {

        public static final Target EVERYONE = new Target(null, null, null, null);
    }

    /** A gift as the client sees it; the times are ISO-8601, expiresAt is null when it never expires. */
    public record GiftInfo(int id, String title, Reward reward, String createdAt, String expiresAt) {
    }

    /** The gifts waiting in a box: how many there are, and the latest of them. */
    public record Pending(int count, List<GiftInfo> gifts) {
    }

    /** What the database answered to a claim: the gift is set when OK. */
    public record ClaimOutcome(Status status, GiftInfo gift) {

        public enum Status { OK, UNAUTHORIZED, NOT_FOUND, ALREADY_CLAIMED, EXPIRED }

        static ClaimOutcome of(Status status) {
            return new ClaimOutcome(status, null);
        }
    }

    /**
     * gift is the gift taken, money and gems the balances afterwards, items the items given and gifts what is left in
     * the box; all are null or empty unless OK.
     */
    public record ClaimResult(ClaimOutcome.Status status, GiftInfo gift, int money, int gems, List<ItemInfo> items,
                              Pending gifts) {

        static ClaimResult refused(ClaimOutcome.Status status) {
            return new ClaimResult(status, null, 0, 0, List.of(), null);
        }
    }

    /** A sending: its id, written on each of its gifts, and how many players received it. */
    public record SendReport(String batch, int sent) {
    }

    // ---- port, implemented by the Panache adapter

    public interface Repository {
        /** The gifts of the user not taken nor expired at {@code now}, the latest first, {@code limit} at most. */
        Uni<Pending> pending(int userId, java.time.Instant now, int limit);

        /** Marks the gift as taken if it is one of the user's, not taken yet and not expired. */
        Uni<ClaimOutcome> claim(int userId, int giftId, java.time.Instant now);

        /** Gives the gift to every player matching the target; emits how many received it. */
        Uni<Integer> send(Target target, String title, Reward reward, Duration expiresIn, String batch,
                          java.time.Instant now);
    }
}
