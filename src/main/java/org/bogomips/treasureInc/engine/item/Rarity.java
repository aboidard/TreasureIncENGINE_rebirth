package org.bogomips.treasureInc.engine.item;

public enum Rarity {
    COMMON("common", 1),
    UNCOMMON("uncommon", 2),
    RARE("rare", 5),
    EPIC("epic", 20),
    LEGENDARY("legendary", 100);

    private final String value;
    private final int coef;

    Rarity(String value, int coef) {
        this.value = value;
        this.coef = coef;
    }

    public String value() {
        return value;
    }

    public int coef() {
        return coef;
    }

    /** @param roll a number in 1..1000 */
    public static Rarity fromRoll(int roll) {
        if (roll <= 650) return COMMON;
        if (roll <= 850) return UNCOMMON;
        if (roll <= 990) return RARE;
        if (roll <= 999) return EPIC;
        return LEGENDARY;
    }

    /** Unknown or null values fall back to COMMON, like the original generator. */
    public static Rarity fromValue(String value) {
        for (Rarity r : values()) {
            if (r.value.equalsIgnoreCase(value)) {
                return r;
            }
        }
        return COMMON;
    }
}
