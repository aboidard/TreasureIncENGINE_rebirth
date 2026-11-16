package org.bogomips.treasureInc.engine.expedition;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Locale;

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

    int cost(String location, int difficulty) {
        return difficulty * config.costPerFloor() * level(location);
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
        return difficulty * config.xpPerFloor() * level(location) * (4 + rarity) / 4;
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
