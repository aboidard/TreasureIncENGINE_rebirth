package org.bogomips.treasureInc.engine.item;

import org.bogomips.treasureInc.engine.item.ItemCatalog.Rarity;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

/**
 * What makes an item equipable: its stats, kept as one JSON object (the "stats" column of the items table).
 * <pre>{"slot": 3, "for": 5, "luk": 2, "spe1": "breakable"}</pre>
 * "slot" is the number of the character slot the item fits in; "for", "con" and "luk" are added to the character's
 * strength, constitution and luck; "spe1", "spe2"... are {@link Skill} ids. An item without stats is not equipable.
 * The numbers and keys are stored in the database and known by the client: never renumber or rename them.
 */
public final class Equipment {

    public static final String SLOT = "slot";
    public static final String STRENGTH = "for";
    public static final String CONSTITUTION = "con";
    public static final String LUCK = "luk";
    /** Prefix of the skill keys: "spe1", "spe2"... */
    public static final String SKILL_PREFIX = "spe";

    /** The slots of a character, by number. */
    public enum Slot {
        HEAD(1),
        ARMOR(2),
        WEAPON(3),
        SHIELD(4),
        BOOTS(5),
        GLOVES(6),
        RING(7),
        AMULET(8),
        TALISMAN(9);

        private final int number;

        Slot(int number) {
            this.number = number;
        }

        public int number() {
            return number;
        }

        public static boolean isValid(int number) {
            return number >= 1 && number <= values().length;
        }
    }

    /** Skills an item can carry; the client only receives the id and shows its own description of it. */
    public enum Skill {
        /** The item may break. Not applied yet: nothing breaks an item for now. */
        BREAKABLE("breakable"),
        /** Each equipped item with this skill has {@link #LUCK_CHARM_CHANCE}% of chance to add one item to the chest. */
        LUCK_CHARM("luck_charm");

        public static final int LUCK_CHARM_CHANCE = 25;

        private final String id;

        Skill(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    /**
     * A family of equipable items: its slot, the stat it gives, its sprites in the client (ItemManager.sprites)
     * and its nouns ("noun,gender"), used instead of the usual item nouns.
     */
    enum Kind {
        SWORD(Slot.WEAPON, STRENGTH, ids(range(91, 94), range(98, 108), range(114, 116)), "épée,F", "lame,F", "glaive,M"),
        SHIELD(Slot.SHIELD, CONSTITUTION, ids(range(148, 152)), "bouclier,M", "écu,M", "targe,F"),
        ARMOR(Slot.ARMOR, CONSTITUTION, ids(range(126, 126), range(130, 134)), "armure,F", "cuirasse,F", "plastron,M"),
        HELMET(Slot.HEAD, CONSTITUTION, ids(range(117, 125)), "casque,M", "heaume,M"),
        BOOTS(Slot.BOOTS, CONSTITUTION, ids(range(135, 139)), "paire de bottes,F"),
        GLOVES(Slot.GLOVES, STRENGTH, ids(range(127, 127), range(140, 140)), "paire de gants,F", "gantelet,M"),
        RING(Slot.RING, LUCK, ids(range(0, 6), range(13, 19), range(26, 32)), "anneau,M", "bague,F"),
        AMULET(Slot.AMULET, LUCK, ids(range(7, 12), range(20, 25)), "amulette,F", "pendentif,M", "collier,M"),
        TALISMAN(Slot.TALISMAN, LUCK, ids(range(39, 45), range(129, 129), range(154, 154)), "talisman,M", "gri-gri,M");

        final Slot slot;
        final String stat;
        final List<Integer> graphics;
        final List<String> nouns;

        Kind(Slot slot, String stat, List<Integer> graphics, String... nouns) {
            this.slot = slot;
            this.stat = stat;
            this.graphics = graphics;
            this.nouns = List.of(nouns);
        }

        private static IntStream range(int first, int last) {
            return IntStream.rangeClosed(first, last);
        }

        private static List<Integer> ids(IntStream... ranges) {
            return Arrays.stream(ranges).flatMapToInt(r -> r).boxed().toList();
        }
    }

    // chances, in percent, of the optional stats of a generated equipment
    private static final int EXTRA_LUCK_CHANCE = 20;
    private static final int FIRST_SKILL_CHANCE = 10;
    private static final int SECOND_SKILL_CHANCE = 20;

    private Equipment() {
    }

    /** The sprites of each kind the client can show: a sprite id must be below {@code graphicsCount}. */
    static Map<Kind, List<Integer>> sprites(int graphicsCount) {
        Map<Kind, List<Integer>> sprites = new LinkedHashMap<>();
        for (Kind kind : Kind.values()) {
            List<Integer> shown = kind.graphics.stream().filter(g -> g < graphicsCount).toList();
            if (!shown.isEmpty()) {
                sprites.put(kind, shown);
            }
        }
        return sprites;
    }

    /**
     * Random stats for an item of this kind: the kind's stat, whose value grows with the rarity, and sometimes
     * some luck and one or two skills.
     */
    static Map<String, Object> randomStats(Kind kind, Rarity rarity, RandomGenerator random) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put(SLOT, kind.slot.number());
        int value = mainValue(rarity, random);
        stats.put(kind.stat, value);
        if (!LUCK.equals(kind.stat) && random.nextInt(100) < EXTRA_LUCK_CHANCE) {
            stats.put(LUCK, 1 + random.nextInt(Math.max(1, value / 3)));
        }
        if (random.nextInt(100) < FIRST_SKILL_CHANCE) {
            Skill[] skills = Skill.values();
            int first = random.nextInt(skills.length);
            stats.put(SKILL_PREFIX + 1, skills[first].id());
            if (random.nextInt(100) < SECOND_SKILL_CHANCE) {
                stats.put(SKILL_PREFIX + 2, skills[(first + 1 + random.nextInt(skills.length - 1)) % skills.length].id());
            }
        }
        return stats;
    }

    private static int mainValue(Rarity rarity, RandomGenerator random) {
        return switch (rarity) {
            case COMMON -> between(1, 5, random);
            case UNCOMMON -> between(4, 10, random);
            case RARE -> between(8, 18, random);
            case EPIC -> between(15, 30, random);
            case LEGENDARY -> between(30, 50, random);
        };
    }

    private static int between(int min, int max, RandomGenerator random) {
        return min + random.nextInt(max - min + 1);
    }

    /** The slot number of an item's stats; null when the item is not equipable. */
    public static Integer slot(Map<String, Object> stats) {
        return stats != null && stats.get(SLOT) instanceof Number n ? n.intValue() : null;
    }

    /** True when one of the "spe" keys of the stats is this skill. */
    public static boolean hasSkill(Map<String, Object> stats, Skill skill) {
        return stats != null && stats.entrySet().stream()
                .anyMatch(e -> e.getKey().startsWith(SKILL_PREFIX) && skill.id().equals(e.getValue()));
    }
}
