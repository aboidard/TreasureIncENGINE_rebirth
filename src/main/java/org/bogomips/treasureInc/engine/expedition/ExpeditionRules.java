package org.bogomips.treasureInc.engine.expedition;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The game rules of an expedition: what is valid, what it costs and what it brings back. */
@ApplicationScoped
class ExpeditionRules {

    private final ExpeditionConfig config;

    ExpeditionRules(ExpeditionConfig config) {
        this.config = config;
    }

    /** The configuration key of a location name, whatever its case; null when the location does not exist. */
    String locationKey(String name) {
        if (name == null) {
            return null;
        }
        String key = name.toLowerCase(Locale.ROOT);
        return config.locations().containsKey(key) ? key : null;
    }

    boolean isValidCase(int caseId) {
        return caseId >= 0 && caseId < config.gridSize();
    }

    boolean isValidDifficulty(int difficulty) {
        return difficulty >= 1 && difficulty <= config.maxDifficulty();
    }

    /** {@code difficulty * costPerFloor * level}; nothing for a location of level 1, so a broke player can go on. */
    int cost(String location, int difficulty) {
        int level = level(location);
        return level <= 1 ? 0 : difficulty * config.costPerFloor() * level;
    }

    /**
     * The chances of each item rarity in the chests of a location, in per mille from common to legendary.
     *
     * @throws IllegalStateException when the configuration does not give five chances adding up to 1000
     */
    List<Integer> loot(String location) {
        List<Integer> loot = config.locations().get(location).loot();
        if (loot.size() != 5 || loot.stream().anyMatch(c -> c < 0)
                || loot.stream().mapToInt(Integer::intValue).sum() != 1000) {
            throw new IllegalStateException("loot of " + location + " must be 5 chances adding up to 1000: " + loot);
        }
        return loot;
    }

    /**
     * {@code round(difficulty * (0.5 + 0.25 * level) * (1 + 0.25 * rarity))}, at least 1 and at most the configured
     * maximum. Computed on integers so that the rounding is exact: both factors are multiples of 1/4.
     */
    int itemCount(String location, int difficulty, int rarity) {
        int items = (difficulty * (2 + level(location)) * (4 + rarity) + 8) / 16;
        return Math.min(config.maxItems(), Math.max(1, items));
    }

    /**
     * The experience points the character leading the expedition earns:
     * {@code difficulty * xpPerFloor * level * (1 + 0.25 * rarity)}, rounded down.
     */
    int xp(String location, int difficulty, int rarity) {
        return xpAtLevel(level(location), difficulty, rarity);
    }

    /** Like {@link #xp}, for a level rather than a location (an event). */
    int xpAtLevel(int level, int difficulty, int rarity) {
        return difficulty * config.xpPerFloor() * level * (4 + rarity) / 4;
    }

    /** The rarity chances of the first location of the story of this level; null when there is none. */
    List<Integer> lootAtLevel(int level) {
        return config.locations().entrySet().stream()
                .filter(e -> e.getValue().level() == level)
                .map(Map.Entry::getKey)
                .sorted()
                .findFirst()
                .map(this::loot)
                .orElse(null);
    }

    /**
     * What an expedition whose character stopped before the end brings back of a full amount (items, experience):
     * the share of the floors whose chest was reached, rounded down.
     */
    static int share(int full, int floorsCompleted, int floors) {
        return floorsCompleted >= floors ? full : full * Math.max(0, floorsCompleted) / floors;
    }

    /** @param roll a number in 1..1000 */
    static int rarityFromRoll(int roll) {
        if (roll <= 650) return 0;
        if (roll <= 850) return 1;
        if (roll <= 990) return 2;
        if (roll <= 999) return 3;
        return 4;
    }

    /** The level of a location, from 1: what is found there, and what is met there, grows with it. */
    int level(String location) {
        return config.locations().get(location).level();
    }
}
