package org.bogomips.treasureInc.engine.quest;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bogomips.treasureInc.engine.quest.QuestCatalog.Definition;
import org.bogomips.treasureInc.engine.quest.QuestCatalog.Metric;
import org.bogomips.treasureInc.engine.quest.QuestCatalog.Scope;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * The quests of each player: a daily board and a weekly one, each holding a few quests drawn from the
 * {@link QuestCatalog}. A board lasts one period (a day, or a week from Monday): the first thing a player does in
 * a new period draws a new board, and what was not claimed on the old one is lost.
 * The boards only live in Redis. Read-modify-write sequences must run inside the user's lock.
 */
@ApplicationScoped
public class QuestBoards implements QuestTracker {

    private final Store store;
    private final int dailyCount;
    private final int weeklyCount;
    private final Clock clock;
    private final RandomGenerator random;

    /**
     * @param dailyCount  how many quests a daily board holds
     * @param weeklyCount how many quests a weekly board holds
     * @param zone        the time zone whose midnight starts a new day
     */
    @Inject
    public QuestBoards(Store store,
                       @ConfigProperty(name = "engine.quests.daily-count", defaultValue = "3") int dailyCount,
                       @ConfigProperty(name = "engine.quests.weekly-count", defaultValue = "3") int weeklyCount,
                       @ConfigProperty(name = "engine.quests.zone", defaultValue = "Europe/Paris") String zone) {
        this(store, dailyCount, weeklyCount, Clock.system(ZoneId.of(zone)), RandomGenerator.getDefault());
    }

    /** @param clock gives the time and the zone of the periods */
    public QuestBoards(Store store, int dailyCount, int weeklyCount, Clock clock, RandomGenerator random) {
        if (dailyCount < 0 || weeklyCount < 0) {
            throw new IllegalArgumentException("engine.quests: daily-count and weekly-count cannot be negative");
        }
        this.store = store;
        this.dailyCount = dailyCount;
        this.weeklyCount = weeklyCount;
        this.clock = clock;
        this.random = random;
    }

    /** The boards of the user for the current day and week, drawn now if the user had none yet. */
    public Uni<State> currentLocked(String publicKey) {
        return store.get(publicKey).flatMap(stored -> {
            State current = refresh(stored);
            return current.equals(stored) ? Uni.createFrom().item(current)
                    : store.put(publicKey, current).replaceWith(current);
        });
    }

    @Override
    public Uni<Void> addLocked(String publicKey, Map<Metric, Integer> amounts) {
        return currentLocked(publicKey).flatMap(state -> {
            State updated = new State(add(state.daily(), amounts), add(state.weekly(), amounts));
            return updated.equals(state) ? Uni.createFrom().voidItem() : store.put(publicKey, updated);
        });
    }

    /** Stores the boards; they must come from {@link #currentLocked}. */
    public Uni<Void> saveLocked(String publicKey, State state) {
        return store.put(publicKey, state);
    }

    /** How many seconds are left before the boards of this scope are replaced. */
    public long resetIn(Scope scope) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate next = scope == Scope.DAILY ? now.toLocalDate().plusDays(1)
                : now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return Math.max(1, Duration.between(now, next.atStartOfDay(now.getZone())).getSeconds());
    }

    /** The current period of a scope: "2026-11-16" for a day, "2026-W47" for a week (ISO: it starts on Monday). */
    String period(Scope scope) {
        LocalDate today = LocalDate.now(clock);
        return scope == Scope.DAILY ? today.toString()
                : today.get(IsoFields.WEEK_BASED_YEAR) + "-W" + today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    private State refresh(State stored) {
        return new State(refresh(stored == null ? null : stored.daily(), Scope.DAILY, dailyCount),
                refresh(stored == null ? null : stored.weekly(), Scope.WEEKLY, weeklyCount));
    }

    /** The board itself while its period lasts; otherwise a new one. */
    private Board refresh(Board board, Scope scope, int count) {
        String period = period(scope);
        // a board holding a quest the catalog no longer has is drawn again
        boolean valid = board != null && period.equals(board.period())
                && board.quests().stream().allMatch(e -> QuestCatalog.get(e.id()) != null);
        return valid ? board : new Board(period, draw(scope, count));
    }

    /** Quests of the scope picked at random, all different. */
    private List<Entry> draw(Scope scope, int count) {
        List<Definition> left = new ArrayList<>(QuestCatalog.of(scope));
        List<Entry> drawn = new ArrayList<>();
        while (drawn.size() < count && !left.isEmpty()) {
            drawn.add(new Entry(left.remove(random.nextInt(left.size())).id(), 0, false));
        }
        return List.copyOf(drawn);
    }

    private static Board add(Board board, Map<Metric, Integer> amounts) {
        return new Board(board.period(), board.quests().stream().map(entry -> {
            Definition quest = QuestCatalog.get(entry.id());
            int amount = Math.max(0, amounts.getOrDefault(quest.metric(), 0));
            // the progress stops at the goal: it is what the client shows
            int progress = (int) Math.min(quest.goal(), (long) entry.progress() + amount);
            return progress == entry.progress() ? entry : new Entry(entry.id(), progress, entry.claimed());
        }).toList(), board.bonusClaimed());
    }

    // ---- values

    /**
     * A quest on a board.
     *
     * @param id       the id of its {@link Definition}
     * @param progress how many times its metric was counted so far, the goal at most
     * @param claimed  true once its reward was taken
     */
    public record Entry(
            @JsonProperty("id") String id,
            @JsonProperty("progress") int progress,
            @JsonProperty("claimed") boolean claimed) {

        public Definition quest() {
            return QuestCatalog.get(id);
        }

        public boolean completed() {
            return progress >= quest().goal();
        }

        /** Completed, and its reward still waits. */
        public boolean claimable() {
            return completed() && !claimed;
        }
    }

    /**
     * The quests of one period, see {@link #period}.
     *
     * @param bonusClaimed true once the reward of the daily bonus ({@link QuestCatalog#BONUS}) was taken; a board
     *                     stored before the bonus existed has no such field, which reads as false
     */
    public record Board(
            @JsonProperty("period") String period,
            @JsonProperty("quests") List<Entry> quests,
            @JsonProperty("bonusClaimed") boolean bonusClaimed) {

        public Board(String period, List<Entry> quests) {
            this(period, quests, false);
        }

        /**
         * The daily bonus on this board: its progress is how many of the quests drawn are completed. Its state is
         * not stored, apart from {@link #bonusClaimed}: it follows the other quests.
         */
        public Entry bonus() {
            Definition bonus = QuestCatalog.BONUS;
            int completed = (int) quests.stream().filter(Entry::completed).count();
            return new Entry(bonus.id(), Math.min(bonus.goal(), completed), bonusClaimed);
        }

        /** A copy where the quest is claimed. */
        Board claimed(String id) {
            if (QuestCatalog.BONUS.id().equals(id)) {
                return new Board(period, quests, true);
            }
            return new Board(period, quests.stream()
                    .map(e -> e.id().equals(id) ? new Entry(e.id(), e.progress(), true) : e).toList(), bonusClaimed);
        }
    }

    /** The boards of a user as kept in Redis. */
    public record State(
            @JsonProperty("daily") Board daily,
            @JsonProperty("weekly") Board weekly) {

        public Board board(Scope scope) {
            return scope == Scope.DAILY ? daily : weekly;
        }

        /** Null when no board holds this quest. The daily bonus is on the daily board. */
        public Entry entry(String id) {
            if (QuestCatalog.BONUS.id().equals(id)) {
                return daily.bonus();
            }
            return java.util.stream.Stream.concat(daily.quests().stream(), weekly.quests().stream())
                    .filter(e -> e.id().equals(id)).findFirst().orElse(null);
        }

        public State claimed(String id) {
            if (QuestCatalog.BONUS.id().equals(id)) {
                return new State(daily.claimed(id), weekly);
            }
            return new State(daily.claimed(id), weekly.claimed(id));
        }

        /** How many rewards wait to be taken, the daily bonus included. */
        public int claimable() {
            return (int) java.util.stream.Stream.concat(daily.quests().stream(), weekly.quests().stream())
                    .filter(Entry::claimable).count() + (daily.bonus().claimable() ? 1 : 0);
        }
    }

    // ---- port, implemented by the Redis adapter

    public interface Store {
        /** Null when the user has no board. */
        Uni<State> get(String publicKey);

        Uni<Void> put(String publicKey, State state);
    }
}
