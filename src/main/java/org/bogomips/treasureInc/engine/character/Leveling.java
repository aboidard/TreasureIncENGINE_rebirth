package org.bogomips.treasureInc.engine.character;

import org.bogomips.treasureInc.engine.character.CharacterCatalog.Info;
import org.bogomips.treasureInc.engine.item.Equipment;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The levels of the characters. Only the experience points a character earned since it was obtained are stored
 * (see {@link CrewStore.Crew#xp}): its level and its stats are computed from them.
 * <ul>
 * <li>A character starts at level {@value #MIN_LEVEL} and stops at level {@value #MAX_LEVEL}.</li>
 * <li>Going from a level to the next one takes {@value #XP_STEP} points times the level: each level is longer than
 * the one before.</li>
 * <li>Each level gained gives stat points, more of them for a better rank (see {@link #pointsPerLevel}).</li>
 * </ul>
 */
public final class Leveling {

    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 100;
    /** The points needed to leave level 1; leaving level n takes n times as many. */
    static final int XP_STEP = 100;
    /** The points of a character at the highest level: it earns nothing more. */
    public static final int MAX_XP = xpForLevel(MAX_LEVEL);

    private Leveling() {
    }

    /** The points to earn at this level to reach the next one; 0 at the highest level. */
    public static int xpToNext(int level) {
        return level >= MAX_LEVEL ? 0 : XP_STEP * level;
    }

    /** The total of points at which the level is reached: 0 for level 1. */
    public static int xpForLevel(int level) {
        return XP_STEP * (level - 1) * level / 2;
    }

    /** The level of a character that earned this total of points. */
    public static int level(int totalXp) {
        int level = MIN_LEVEL;
        while (level < MAX_LEVEL && totalXp >= xpForLevel(level + 1)) {
            level++;
        }
        return level;
    }

    /** The total of points after a gain: never negative, never above {@link #MAX_XP}. */
    public static int add(int totalXp, int gained) {
        return (int) Math.max(0, Math.min(MAX_XP, (long) Math.max(0, totalXp) + Math.max(0, gained)));
    }

    /** Where a character that earned this total of points stands. */
    public static Progress progress(int totalXp) {
        int total = add(totalXp, 0);
        int level = level(total);
        return new Progress(level, total - xpForLevel(level), xpToNext(level));
    }

    /** How many stat points a character of this rank gains at each level: 1 for a D, up to 7 for a SSS. */
    public static int pointsPerLevel(CharacterCatalog.Rank rank) {
        return rank.ordinal() + 1;
    }

    /**
     * The stats of a character at a level, with the keys of the stats of an item: its own stats at level 1, plus
     * the points of each level gained. The points go to its stats in proportion to its own stats, one at a time, so
     * a character keeps its profile and a stat never goes down from a level to the next one.
     */
    public static Map<String, Object> stats(Info info, int level) {
        int[] base = {info.strength(), info.constitution(), info.agility(), info.luck()};
        int[] given = new int[base.length];
        boolean flat = Arrays.stream(base).allMatch(stat -> stat <= 0);
        int points = (Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level)) - 1) * pointsPerLevel(info.rank());
        for (int p = 0; p < points; p++) {
            int best = 0;
            for (int i = 1; i < base.length; i++) {
                // the stat which is the furthest behind its share: base / (given + 1), compared without dividing
                long candidate = (long) (flat ? 1 : base[i]) * (given[best] + 1);
                long current = (long) (flat ? 1 : base[best]) * (given[i] + 1);
                if (candidate > current) {
                    best = i;
                }
            }
            given[best]++;
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put(Equipment.STRENGTH, base[0] + given[0]);
        stats.put(Equipment.CONSTITUTION, base[1] + given[1]);
        stats.put(Equipment.AGILITY, base[2] + given[2]);
        stats.put(Equipment.LUCK, base[3] + given[3]);
        return stats;
    }

    /**
     * @param level  from {@link #MIN_LEVEL} to {@link #MAX_LEVEL}
     * @param xp     the points earned since this level was reached
     * @param xpNext the points this level takes; 0 at the highest level
     */
    public record Progress(int level, int xp, int xpNext) {
    }
}
