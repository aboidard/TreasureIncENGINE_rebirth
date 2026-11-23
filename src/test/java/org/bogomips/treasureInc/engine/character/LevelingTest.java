package org.bogomips.treasureInc.engine.character;

import org.bogomips.treasureInc.engine.character.CharacterCatalog.Info;
import org.bogomips.treasureInc.engine.character.Leveling.Progress;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LevelingTest {

    private static int total(Map<String, Object> stats) {
        return stats.values().stream().mapToInt(v -> (Integer) v).sum();
    }

    @Test
    void aCharacterStartsAtLevelOne() {
        assertEquals(new Progress(1, 0, 100), Leveling.progress(0));
        assertEquals(new Progress(1, 99, 100), Leveling.progress(99));
        assertEquals(new Progress(1, 0, 100), Leveling.progress(-5));
    }

    @Test
    void levelsAreReachedAtTheirThreshold() {
        assertEquals(new Progress(2, 0, 200), Leveling.progress(100));
        assertEquals(new Progress(2, 199, 200), Leveling.progress(299));
        assertEquals(new Progress(3, 0, 300), Leveling.progress(300));
        assertEquals(new Progress(4, 50, 400), Leveling.progress(650));
    }

    @Test
    void eachLevelIsLongerThanTheOneBefore() {
        for (int level = 2; level < Leveling.MAX_LEVEL; level++) {
            assertTrue(Leveling.xpToNext(level) > Leveling.xpToNext(level - 1), "level " + level);
            assertEquals(Leveling.xpForLevel(level) + Leveling.xpToNext(level), Leveling.xpForLevel(level + 1));
            assertEquals(level, Leveling.level(Leveling.xpForLevel(level)));
            assertEquals(level - 1, Leveling.level(Leveling.xpForLevel(level) - 1));
        }
    }

    @Test
    void theLevelStopsAtTheMaximum() {
        assertEquals(495000, Leveling.MAX_XP);
        assertEquals(new Progress(100, 0, 0), Leveling.progress(Leveling.MAX_XP));
        assertEquals(new Progress(99, 9899, 9900), Leveling.progress(Leveling.MAX_XP - 1));
        assertEquals(Leveling.MAX_XP, Leveling.add(Leveling.MAX_XP - 10, 500));
        assertEquals(Leveling.MAX_XP, Leveling.add(Leveling.MAX_XP, Integer.MAX_VALUE));
        assertEquals(new Progress(100, 0, 0), Leveling.progress(Integer.MAX_VALUE));
        assertEquals(40, Leveling.add(40, -10), "experience is never lost");
    }

    @Test
    void theStatsAtLevelOneAreThoseOfTheCharacter() {
        for (Info info : CharacterCatalog.all()) {
            assertEquals(info.stats(), Leveling.stats(info, 1), info.name());
        }
    }

    @Test
    void eachLevelGivesThePointsOfTheRank() {
        for (Info info : CharacterCatalog.all()) {
            int points = info.rank().ordinal() + 1;
            Map<String, Object> before = Leveling.stats(info, 1);
            for (int level = 2; level <= Leveling.MAX_LEVEL; level++) {
                Map<String, Object> stats = Leveling.stats(info, level);
                assertEquals(total(before) + points, total(stats), info.name() + " level " + level);
                for (String stat : stats.keySet()) {
                    assertTrue((Integer) stats.get(stat) >= (Integer) before.get(stat),
                            info.name() + " loses " + stat + " at level " + level);
                }
                before = stats;
            }
        }
    }

    @Test
    void thePointsFollowTheProfileOfTheCharacter() {
        // Rouage le chevalier mécanique, rank B (3 points a level): 9 strength, 3 constitution, 3 agility, 4 luck
        Info zeph = CharacterCatalog.get(12);
        // level 20: 19 levels gained, 57 points, shared as 9/19, 3/19, 3/19 and 4/19
        assertEquals(Map.of("for", 9 + 27, "con", 3 + 9, "agi", 3 + 9, "luk", 4 + 12), Leveling.stats(zeph, 20));
    }

    @Test
    void aBetterRankGainsMore() {
        assertEquals(1, Leveling.pointsPerLevel(CharacterCatalog.Rank.D));
        assertEquals(7, Leveling.pointsPerLevel(CharacterCatalog.Rank.SSS));
        // Durgan has no luck: he never gains any
        assertEquals(0, Leveling.stats(CharacterCatalog.get(6), 100).get("luk"));
    }
}
