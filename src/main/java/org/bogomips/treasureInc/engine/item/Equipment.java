package org.bogomips.treasureInc.engine.item;

import org.bogomips.treasureInc.engine.item.ItemCatalog.Rarity;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

/**
 * What makes an item equipable: its stats, kept as one JSON object (the "stats" column of the items table).
 * <pre>{"slot": 3, "for": 5, "luk": 2, "spe1": "breakable"}</pre>
 * "slot" is the number of the character slot the item fits in; "for", "con", "agi" and "luk" are added to the
 * character's strength, constitution, agility and luck; "spe1", "spe2"... are {@link Skill} ids. An item without
 * stats is not equipable.
 * The numbers and keys are stored in the database and known by the client: never renumber or rename them.
 */
public final class Equipment {

    public static final String SLOT = "slot";
    public static final String STRENGTH = "for";
    public static final String CONSTITUTION = "con";
    public static final String AGILITY = "agi";
    public static final String LUCK = "luk";
    /** Prefix of the skill keys: "spe1", "spe2"... */
    public static final String SKILL_PREFIX = "spe";

    /** Number of sprites of the sheet of items of the client: an item that is not an equipment gets one of them. */
    static final int SHEET_SPRITES = 181;
    /** Number of skins the client draws itself for each kind of equipment, after the sprites of the sheet. */
    static final int SKINS_PER_KIND = 16;

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

    /**
     * The skills of the game: a character has those of its own (see CharacterCatalog) and those of the items it
     * wears. The client only receives the id and shows its own description of it. A skill counts once, however
     * many times a character has it, except the luck charm, which works for each item carrying it.
     * What the fight skills do is in the combat package (Fighter for the passive ones, Combat for the active ones).
     */
    public enum Skill {
        /** The item may break. Not applied yet: nothing breaks an item for now. */
        BREAKABLE("breakable", Type.OTHER),
        /** Each equipped item with this skill has {@link #LUCK_CHARM_CHANCE}% of chance to add one item to the chest. */
        LUCK_CHARM("luck_charm", Type.OTHER, "RING", "AMULET", "TALISMAN"),

        // ---- active: used by the character during a fight, when the circumstances call for them
        /** The enemy has less than 10 % of its life: a critical hit doing twice the damage. Once a fight. */
        FINISH_HIM("finish_him", Type.ACTIVE, "SWORD"),
        /** When the fight starts: defense +50 % for 5 seconds. */
        ENGAGEMENT("engagement", Type.ACTIVE, "SHIELD", "ARMOR"),
        /** Life at 30 % or less: recovers 25 % of the highest life. Once a fight. */
        SECOND_WIND("second_wind", Type.ACTIVE, "AMULET", "ARMOR"),
        /** Life at 50 % or less: attacks 50 % faster for 6 seconds. Once a fight. */
        BERSERK("berserk", Type.ACTIVE, "GLOVES", "SWORD"),
        /** After a hit received: 25 % of chance to strike back at once. */
        RIPOSTE("riposte", Type.ACTIVE, "SHIELD", "SWORD"),
        /** After 3 seconds then every 8 seconds: stuns the enemy for 1.5 second. */
        SHIELD_BASH("shield_bash", Type.ACTIVE, "SHIELD"),
        /** Every fourth attack hits twice. */
        DOUBLE_STRIKE("double_strike", Type.ACTIVE, "GLOVES", "SWORD"),
        /** When the fight starts: an attack doing 150 % of the damage. */
        AMBUSH("ambush", Type.ACTIVE, "BOOTS"),
        /** Every 10 seconds, an attack poisons the enemy: 30 % of the attack each second for 5 seconds. */
        POISON("poison", Type.ACTIVE, "SWORD", "RING"),
        /** The first deadly hit of the expedition leaves 1 point of life, and nothing hurts for 2 seconds. */
        LAST_STAND("last_stand", Type.ACTIVE, "TALISMAN", "HELMET"),
        /** When the fight starts: the attack of the enemy -25 % for 6 seconds. */
        WAR_CRY("war_cry", Type.ACTIVE, "HELMET"),
        /** Every 6 seconds, when wounded: an attack that gives back the damage it does as life. */
        DRAIN("drain", Type.ACTIVE, "AMULET", "SWORD"),

        // ---- passive: they last for the whole expedition
        /** Life +20 %. */
        TOUGH("tough", Type.PASSIVE, "ARMOR", "HELMET"),
        /** Attack +15 %. */
        SHARP("sharp", Type.PASSIVE, "SWORD", "GLOVES"),
        /** Attacks 20 % faster. */
        SWIFT("swift", Type.PASSIVE, "BOOTS"),
        /** Defense +25 %. */
        THICK_SKIN("thick_skin", Type.PASSIVE, "ARMOR", "SHIELD"),
        /** Chance of a critical hit +15 points. */
        KEEN_EYE("keen_eye", Type.PASSIVE, "HELMET", "RING"),
        /** Barricades are destroyed twice as fast. */
        DEMOLISHER("demolisher", Type.PASSIVE, "GLOVES"),
        /** Recovers 10 % of the highest life at the end of each floor. */
        REGENERATION("regeneration", Type.PASSIVE, "AMULET", "RING"),
        /** Gives 20 % of the damage received back to the attacker. */
        THORNS("thorns", Type.PASSIVE, "ARMOR", "SHIELD"),
        /** Each monster beaten has 20 % of chance to add one item to the chest. */
        LOOTER("looter", Type.PASSIVE, "TALISMAN", "GLOVES"),
        /** 15 % of chance to dodge an attack. */
        DODGE("dodge", Type.PASSIVE, "BOOTS");

        /** ACTIVE and PASSIVE are the fight skills; OTHER has nothing to do with the fights. */
        public enum Type { OTHER, ACTIVE, PASSIVE }

        public static final int LUCK_CHARM_CHANCE = 25;

        private final String id;
        private final Type type;
        /** Names of the kinds of items that can carry the skill; empty: every kind. */
        private final Set<String> kinds;

        Skill(String id, Type type, String... kinds) {
            this.id = id;
            this.type = type;
            this.kinds = Set.of(kinds);
        }

        public String id() {
            return id;
        }

        public Type type() {
            return type;
        }

        /** Whether an item of this kind can carry the skill. */
        boolean fits(Kind kind) {
            return kinds.isEmpty() || kinds.contains(kind.name());
        }

        /** Null when no skill has this id. */
        public static Skill fromId(Object id) {
            for (Skill skill : values()) {
                if (skill.id.equals(id)) {
                    return skill;
                }
            }
            return null;
        }
    }

    /**
     * A family of equipable items: its slot, the stat it gives, its sprites in the client (ItemManager.sprites)
     * and its nouns ("noun,gender"), used instead of the usual item nouns.
     * The sprites below {@link #SHEET_SPRITES} are those of the sprite sheet of the client; the others are the
     * skins the client draws itself (ItemArt.cs): {@link #SKINS_PER_KIND} for each kind, in the order of the kinds.
     */
    enum Kind {
        SWORD(Slot.WEAPON, STRENGTH, ids(range(91, 94), range(98, 108), range(114, 116), skins(0)), "épée,F", "lame,F", "glaive,M"),
        SHIELD(Slot.SHIELD, CONSTITUTION, ids(range(148, 152), skins(1)), "bouclier,M", "écu,M", "targe,F"),
        // 130, 133 and 134 are helmets and 132 a pair of boots, though they sit among the armors of the sprite sheet
        ARMOR(Slot.ARMOR, CONSTITUTION, ids(range(126, 126), range(131, 131), skins(2)), "armure,F", "cuirasse,F", "plastron,M"),
        HELMET(Slot.HEAD, CONSTITUTION, ids(range(117, 125), range(130, 130), range(133, 134), skins(3)), "casque,M", "heaume,M"),
        BOOTS(Slot.BOOTS, AGILITY, ids(range(132, 132), range(135, 139), skins(4)), "paire de bottes,F"),
        GLOVES(Slot.GLOVES, STRENGTH, ids(range(127, 127), range(140, 140), skins(5)), "paire de gants,F", "gantelet,M"),
        RING(Slot.RING, LUCK, ids(range(0, 6), range(13, 19), range(26, 32), skins(6)), "anneau,M", "bague,F"),
        AMULET(Slot.AMULET, LUCK, ids(range(7, 12), range(20, 25), skins(7)), "amulette,F", "pendentif,M", "collier,M"),
        TALISMAN(Slot.TALISMAN, LUCK, ids(range(39, 45), range(129, 129), range(154, 154), skins(8)), "talisman,M", "gri-gri,M");

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

        /** The skills an item of this kind can carry. */
        List<Skill> skills() {
            return Arrays.stream(Skill.values()).filter(skill -> skill.fits(this)).toList();
        }

        private static IntStream range(int first, int last) {
            return IntStream.rangeClosed(first, last);
        }

        /** The skins the client draws for the kind at this place in ItemArt.cs. */
        private static IntStream skins(int kindIndex) {
            int first = SHEET_SPRITES + kindIndex * SKINS_PER_KIND;
            return IntStream.range(first, first + SKINS_PER_KIND);
        }

        private static List<Integer> ids(IntStream... ranges) {
            return Arrays.stream(ranges).flatMapToInt(r -> r).boxed().toList();
        }
    }

    // chances, in percent, of the optional stats of a generated equipment
    private static final int EXTRA_LUCK_CHANCE = 20;
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

    /** Chance, in percent, that an equipment of this rarity carries a skill: the rarer, the more often. */
    static int skillChance(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> 10;
            case UNCOMMON -> 20;
            case RARE -> 35;
            case EPIC -> 60;
            case LEGENDARY -> 100;
        };
    }

    /**
     * Random stats for an item of this kind: the kind's stat, whose value grows with the rarity, and sometimes
     * some luck and one or two of the skills its kind can carry.
     */
    static Map<String, Object> randomStats(Kind kind, Rarity rarity, RandomGenerator random) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put(SLOT, kind.slot.number());
        int value = mainValue(rarity, random);
        stats.put(kind.stat, value);
        if (!LUCK.equals(kind.stat) && random.nextInt(100) < EXTRA_LUCK_CHANCE) {
            stats.put(LUCK, 1 + random.nextInt(Math.max(1, value / 3)));
        }
        if (random.nextInt(100) < skillChance(rarity)) {
            List<Skill> skills = kind.skills();
            int first = random.nextInt(skills.size());
            stats.put(SKILL_PREFIX + 1, skills.get(first).id());
            if (skills.size() > 1 && random.nextInt(100) < SECOND_SKILL_CHANCE) {
                stats.put(SKILL_PREFIX + 2,
                        skills.get((first + 1 + random.nextInt(skills.size() - 1)) % skills.size()).id());
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

    /** The value of a stat ("for", "con", "agi", "luk") in the stats of an item or of a character; 0 when absent. */
    public static int stat(Map<String, Object> stats, String key) {
        return stats != null && stats.get(key) instanceof Number n ? n.intValue() : 0;
    }

    /** The skills named by the "spe" keys of the stats of an item. */
    public static Set<Skill> skills(Map<String, Object> stats) {
        Set<Skill> skills = EnumSet.noneOf(Skill.class);
        if (stats != null) {
            stats.forEach((key, value) -> {
                Skill skill = key.startsWith(SKILL_PREFIX) ? Skill.fromId(value) : null;
                if (skill != null) {
                    skills.add(skill);
                }
            });
        }
        return skills;
    }
}
