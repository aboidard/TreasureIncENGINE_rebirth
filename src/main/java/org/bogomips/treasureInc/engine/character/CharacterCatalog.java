package org.bogomips.treasureInc.engine.character;

import org.bogomips.treasureInc.engine.item.Equipment;
import org.bogomips.treasureInc.engine.item.Equipment.Skill;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every character of the game, and the portals they are summoned from (see {@link RecruitService}).
 * The number of a character is stored in the database and known by the client, which has its portrait: never
 * renumber a character. Adding one: a line in {@link #ALL}, and its portrait in the client.
 */
public final class CharacterCatalog {

    /**
     * The ranks from the weakest to the strongest, with their chance of being drawn out of {@link #TOTAL_WEIGHT}:
     * D 40 %, C 30 %, B 17 %, A 9 %, S 3 %, SS 0.9 %, SSS 0.1 %.
     */
    public enum Rank {
        D(400), C(300), B(170), A(90), S(30), SS(9), SSS(1);

        public static final int TOTAL_WEIGHT = Arrays.stream(values()).mapToInt(Rank::weight).sum();

        private final int weight;

        Rank(int weight) {
            this.weight = weight;
        }

        public int weight() {
            return weight;
        }

        /** Chance of drawing this rank, in percent. */
        public double percent() {
            return weight * 100.0 / TOTAL_WEIGHT;
        }

        /** The rank of a roll in [0, TOTAL_WEIGHT): the first rolls are the SSS, the last ones the D. */
        public static Rank fromRoll(int roll) {
            int limit = 0;
            for (int i = values().length - 1; i > 0; i--) {
                limit += values()[i].weight;
                if (roll < limit) {
                    return values()[i];
                }
            }
            return D;
        }
    }

    /**
     * A character: strength, constitution, agility and luck are its own stats, to which its equipment is added.
     * skills are the skills it has by itself, whatever it wears: none for the weakest ranks, more of them for a
     * rarer one (see {@link #skillCount}).
     */
    public record Info(int num, String name, Rank rank, int strength, int constitution, int agility, int luck,
                       List<Skill> skills) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("num", num);
            m.put("name", name);
            m.put("rank", rank.name());
            m.put("stats", stats());
            m.put("skills", skillIds());
            return m;
        }

        /** Same keys as the stats of an item. */
        public Map<String, Object> stats() {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put(Equipment.STRENGTH, strength);
            stats.put(Equipment.CONSTITUTION, constitution);
            stats.put(Equipment.AGILITY, agility);
            stats.put(Equipment.LUCK, luck);
            return stats;
        }

        public List<String> skillIds() {
            return skills.stream().map(Skill::id).toList();
        }
    }

    /** How many skills a character of this rank has by itself: none for a D or a C, then 1 for a B up to 5 for a SSS. */
    public static int skillCount(Rank rank) {
        return Math.max(0, rank.ordinal() - 1);
    }

    /** A portal: a draw first rolls a rank, then picks one of the characters of the portal with this rank. */
    public record Portal(String id, String name, List<Info> characters) {

        /** The characters of a rank; when the portal has none, those of the closest lower rank that has some. */
        public List<Info> ofRank(Rank rank) {
            for (int i = rank.ordinal(); i >= 0; i--) {
                Rank r = Rank.values()[i];
                List<Info> found = characters.stream().filter(c -> c.rank() == r).toList();
                if (!found.isEmpty()) {
                    return found;
                }
            }
            return characters;
        }
    }

    /** The first three are the characters every user starts with (engine.characters.count). */
    private static final List<Info> ALL = List.of(
            character(1, "Kentucky Johanes", Rank.C, 4, 3, 3, 3),
            character(2, "Brok le quartier-maître", Rank.D, 2, 3, 1, 1),
            character(3, "Vax le caporal", Rank.D, 3, 2, 2, 1),
            character(4, "Gnik le gobelin", Rank.D, 1, 1, 2, 4),
            character(5, "Maëlle l'écuyère", Rank.D, 1, 2, 1, 3),
            character(6, "Durgan le nain porteur", Rank.D, 2, 4, 1, 0),
            character(7, "Samir l'explorateur des sables", Rank.C, 2, 3, 3, 5),
            character(8, "Grokk l'orque", Rank.C, 5, 4, 2, 1),
            character(9, "Pattes-de-Velours la féline", Rank.C, 3, 2, 5, 5),
            character(10, "Brann Cuivrebarbe", Rank.C, 2, 2, 2, 6),
            character(11, "Dame Aurore la chevalière", Rank.B, 7, 5, 4, 4, Skill.WAR_CRY),
            character(12, "Rouage le chevalier mécanique", Rank.B, 9, 3, 3, 4, Skill.DEMOLISHER),
            character(13, "Tariq le gardien turquoise", Rank.B, 3, 8, 2, 5, Skill.REGENERATION),
            character(14, "Brunhilde la Nordique", Rank.B, 5, 9, 2, 2, Skill.THICK_SKIN),
            character(15, "Sylvaëlle l'elfe écarlate", Rank.A, 13, 7, 8, 4, Skill.DOUBLE_STRIKE, Skill.SHARP),
            character(16, "Elwyn l'elfe d'argent", Rank.A, 5, 6, 5, 13, Skill.LOOTER, Skill.KEEN_EYE),
            character(17, "Rakham le vieux paladin", Rank.A, 8, 11, 4, 5, Skill.ENGAGEMENT, Skill.TOUGH),
            character(18, "Aurélio le guerrier d'or", Rank.S, 15, 10, 14, 11, Skill.AMBUSH, Skill.SWIFT, Skill.DODGE),
            character(19, "Baron Minuit le chevalier pourpre", Rank.SS, 18, 16, 12, 16,
                    Skill.DRAIN, Skill.POISON, Skill.FINISH_HIM, Skill.KEEN_EYE),
            character(20, "Malphas le Cornu", Rank.SSS, 22, 22, 18, 26,
                    Skill.LAST_STAND, Skill.SECOND_WIND, Skill.RIPOSTE, Skill.REGENERATION, Skill.LOOTER));

    private static Info character(int num, String name, Rank rank, int strength, int constitution, int agility,
                                  int luck, Skill... skills) {
        return new Info(num, name, rank, strength, constitution, agility, luck, List.of(skills));
    }

    private static final List<Portal> PORTALS = List.of(
            new Portal("masterpack1", "Masterpack vol. 1", ALL));

    /** Fails at startup rather than at the first draw if the list above is edited wrongly. */
    static {
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i).num() != i + 1) {
                throw new IllegalStateException("Characters must be numbered from 1 without a gap: see number "
                        + ALL.get(i).num());
            }
            if (ALL.get(i).skills().size() != skillCount(ALL.get(i).rank())) {
                throw new IllegalStateException(ALL.get(i).name() + " must have " + skillCount(ALL.get(i).rank())
                        + " skills, as every " + ALL.get(i).rank());
            }
        }
    }

    private CharacterCatalog() {
    }

    /** Null when no character has this number. */
    public static Info get(int num) {
        return num >= 1 && num <= ALL.size() ? ALL.get(num - 1) : null;
    }

    public static boolean exists(int num) {
        return get(num) != null;
    }

    public static List<Info> all() {
        return ALL;
    }

    public static List<Portal> portals() {
        return PORTALS;
    }

    /** Null when no portal has this id. */
    public static Portal portal(String id) {
        return PORTALS.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }
}
