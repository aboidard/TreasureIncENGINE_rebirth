package org.bogomips.treasureInc.engine.expedition;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExpeditionRulesTest {

    /** The values of application.yml. */
    static ExpeditionConfig config() {
        return new ExpeditionConfig() {
            public int gridSize() {
                return 18;
            }

            public int maxDifficulty() {
                return 10;
            }

            public int maxItems() {
                return 15;
            }

            public int costPerFloor() {
                return 100;
            }

            public Duration digTtl() {
                return Duration.ofHours(24);
            }

            public Duration ttl() {
                return Duration.ofDays(7);
            }

            public Map<String, Location> locations() {
                return Map.of("mordak", () -> 1, "gardakan", () -> 2, "bobafett", () -> 3);
            }
        };
    }

    private final ExpeditionRules rules = new ExpeditionRules(config());

    private void assertItemCounts(String location, int difficulty, int... countByRarity) {
        for (int rarity = 0; rarity < countByRarity.length; rarity++) {
            assertEquals(countByRarity[rarity], rules.itemCount(location, difficulty, rarity),
                    location + " difficulty " + difficulty + " rarity " + rarity);
        }
    }

    @Test
    void itemCountTable() {
        assertItemCounts("mordak", 1, 1, 1, 1, 1, 2);
        assertItemCounts("bobafett", 1, 1, 2, 2, 2, 3);
        assertItemCounts("mordak", 5, 4, 5, 6, 7, 8);
        assertItemCounts("gardakan", 5, 5, 6, 8, 9, 10);
        assertItemCounts("bobafett", 5, 6, 8, 9, 11, 13);
        assertItemCounts("mordak", 10, 8, 9, 11, 13, 15);
        assertItemCounts("gardakan", 10, 10, 13, 15, 15, 15);
        assertItemCounts("bobafett", 10, 13, 15, 15, 15, 15);
    }

    @Test
    void itemCountStaysBetweenOneAndTheMaximum() {
        for (String location : config().locations().keySet()) {
            for (int difficulty = 1; difficulty <= 10; difficulty++) {
                for (int rarity = 0; rarity <= 4; rarity++) {
                    int count = rules.itemCount(location, difficulty, rarity);
                    assertTrue(count >= 1 && count <= 15, location + " " + difficulty + " " + rarity + " : " + count);
                }
            }
        }
    }

    @Test
    void costDependsOnDifficultyAndLocation() {
        assertEquals(100, rules.cost("mordak", 1));
        assertEquals(1000, rules.cost("mordak", 10));
        assertEquals(1000, rules.cost("gardakan", 5));
        assertEquals(3000, rules.cost("bobafett", 10));
    }

    @Test
    void rarityTable() {
        assertEquals(0, ExpeditionRules.rarityFromRoll(1));
        assertEquals(0, ExpeditionRules.rarityFromRoll(650));
        assertEquals(1, ExpeditionRules.rarityFromRoll(651));
        assertEquals(1, ExpeditionRules.rarityFromRoll(850));
        assertEquals(2, ExpeditionRules.rarityFromRoll(851));
        assertEquals(2, ExpeditionRules.rarityFromRoll(990));
        assertEquals(3, ExpeditionRules.rarityFromRoll(991));
        assertEquals(3, ExpeditionRules.rarityFromRoll(999));
        assertEquals(4, ExpeditionRules.rarityFromRoll(1000));
    }

    @Test
    void locationNamesIgnoreCase() {
        assertEquals("mordak", rules.locationKey("Mordak"));
        assertEquals("bobafett", rules.locationKey("BOBAFETT"));
        assertNull(rules.locationKey("morgoth"));
        assertNull(rules.locationKey(null));
    }

    @Test
    void limits() {
        assertTrue(rules.isValidCase(0));
        assertTrue(rules.isValidCase(17));
        assertFalse(rules.isValidCase(-1));
        assertFalse(rules.isValidCase(18));
        assertTrue(rules.isValidDifficulty(1));
        assertTrue(rules.isValidDifficulty(10));
        assertFalse(rules.isValidDifficulty(0));
        assertFalse(rules.isValidDifficulty(11));
    }
}
