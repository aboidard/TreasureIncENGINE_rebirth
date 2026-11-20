package org.bogomips.treasureInc.engine.quest;

import java.util.List;
import java.util.Locale;

/**
 * The quests of the game: what a player has to do, how many times, and what it earns.
 * Each day and each week a few of them are drawn for every player (see {@link QuestBoards}). A weekly quest asks
 * for the same kind of thing as a daily one, on a larger scale.
 * The client only receives the metric and the goal of a quest: it writes the sentence itself.
 */
public final class QuestCatalog {

    /** What a quest counts. The lowercase name is the "metric" sent to the client. */
    public enum Metric {
        /** Expeditions whose chest was opened. */
        EXPEDITIONS,
        /** Floors finished by the characters. */
        FLOORS,
        /** Expeditions whose character reached the last floor. */
        CLEARS,
        /** Monsters fought, guardians included, whoever won. */
        MONSTERS,
        /** Items found in the chests of the expeditions. */
        ITEMS,
        /** Items found in the chests that can be equipped. */
        EQUIPMENT,
        /** Characters summoned from a portal, duplicates included. */
        RECRUITS,
        /** Items sold. */
        SALES,
        /** Cases dug (digging the same case again does not count). */
        DIGS,
        /** Other daily quests completed: only counted by the daily bonus ({@link QuestCatalog#BONUS}), never drawn. */
        QUESTS;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Scope {
        DAILY, WEEKLY;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * @param id    never changes: it is what the boards of the players hold
     * @param goal  how many times the metric must be counted
     * @param money what claiming the quest pays
     * @param items how many items claiming the quest gives
     * @param gems  how many gems claiming the quest gives
     */
    public record Definition(String id, Scope scope, Metric metric, int goal, int money, int items, int gems) {
    }

    /**
     * The daily bonus: always the same, on every daily board, next to the quests drawn. It is completed with
     * {@code goal} of the other daily quests (engine.quests.daily-count must not be lower) and pays gems.
     */
    public static final Definition BONUS = new Definition("daily_bonus", Scope.DAILY, Metric.QUESTS, 3, 0, 0, 1000);

    private static final List<Definition> ALL = List.of(
            daily(Metric.EXPEDITIONS, 5, 1500, 2),
            daily(Metric.FLOORS, 25, 1500, 2),
            daily(Metric.CLEARS, 3, 2000, 2),
            daily(Metric.MONSTERS, 20, 2500, 3),
            daily(Metric.ITEMS, 100, 2500, 3),
            daily(Metric.EQUIPMENT, 2, 2000, 2),
            daily(Metric.RECRUITS, 20, 5000, 3),
            daily(Metric.SALES, 30, 1000, 2),
            daily(Metric.DIGS, 10, 1500, 2),
            weekly(Metric.EXPEDITIONS, 30, 10000, 8),
            weekly(Metric.FLOORS, 150, 10000, 8),
            weekly(Metric.CLEARS, 15, 12000, 8),
            weekly(Metric.MONSTERS, 100, 15000, 10),
            weekly(Metric.ITEMS, 500, 15000, 10),
            weekly(Metric.EQUIPMENT, 10, 12000, 8),
            weekly(Metric.RECRUITS, 50, 20000, 10),
            weekly(Metric.SALES, 200, 8000, 8),
            weekly(Metric.DIGS, 50, 10000, 8));

    private QuestCatalog() {
    }

    /** One quest per metric and scope, so the quests drawn together never count the same thing. */
    private static Definition daily(Metric metric, int goal, int money, int items) {
        return new Definition("daily_" + metric.key(), Scope.DAILY, metric, goal, money, items, 0);
    }

    private static Definition weekly(Metric metric, int goal, int money, int items) {
        return new Definition("weekly_" + metric.key(), Scope.WEEKLY, metric, goal, money, items, 0);
    }

    /** The quests that can be drawn for a board of this scope (the bonus is not one of them). */
    public static List<Definition> of(Scope scope) {
        return ALL.stream().filter(d -> d.scope() == scope).toList();
    }

    /** Null when no quest has this id. */
    public static Definition get(String id) {
        if (BONUS.id().equals(id)) {
            return BONUS;
        }
        return ALL.stream().filter(d -> d.id().equals(id)).findFirst().orElse(null);
    }
}
