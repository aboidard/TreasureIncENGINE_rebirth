package org.bogomips.treasureInc.engine.character;

import org.bogomips.treasureInc.engine.item.Equipment;

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

    /** A character: strength, constitution and luck are its own stats, to which its equipment is added. */
    public record Info(int num, String name, Rank rank, int strength, int constitution, int luck) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("num", num);
            m.put("name", name);
            m.put("rank", rank.name());
            m.put("stats", stats());
            return m;
        }

        /** Same keys as the stats of an item. */
        public Map<String, Object> stats() {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put(Equipment.STRENGTH, strength);
            stats.put(Equipment.CONSTITUTION, constitution);
            stats.put(Equipment.LUCK, luck);
            return stats;
        }
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
            new Info(1, "Kentucky Johanes", Rank.C, 4, 3, 3),
            new Info(2, "Brok le quartier-maître", Rank.D, 2, 3, 1),
            new Info(3, "Vax le caporal", Rank.D, 3, 2, 1),
            new Info(4, "Gédéon le stagiaire", Rank.D, 1, 1, 4),
            new Info(5, "Mireille la comptable", Rank.D, 1, 2, 3),
            new Info(6, "Bébert le porteur", Rank.D, 2, 4, 0),
            new Info(7, "Olga la cartographe", Rank.C, 2, 3, 5),
            new Info(8, "Sergent Pépin", Rank.C, 5, 4, 1),
            new Info(9, "Lulu la crocheteuse", Rank.C, 3, 2, 5),
            new Info(10, "Fernand le sourcier", Rank.C, 2, 2, 6),
            new Info(11, "Capitaine Morgane", Rank.B, 7, 5, 4),
            new Info(12, "Zeph l'artificier", Rank.B, 9, 3, 4),
            new Info(13, "Sœur Baluchon", Rank.B, 3, 8, 5),
            new Info(14, "Igor le démineur", Rank.B, 5, 9, 2),
            new Info(15, "Dame Isolde la sabreuse", Rank.A, 13, 7, 4),
            new Info(16, "Professeur Tournepierre", Rank.A, 5, 6, 13),
            new Info(17, "Rakham le Vieux", Rank.A, 8, 11, 5),
            new Info(18, "Athéna Vif-Argent", Rank.S, 15, 10, 11),
            new Info(19, "Baron Minuit", Rank.SS, 18, 16, 16),
            new Info(20, "L'Oracle aux Mille Clés", Rank.SSS, 22, 22, 26));

    private static final List<Portal> PORTALS = List.of(
            new Portal("masterpack1", "Masterpack vol. 1", ALL));

    /** Fails at startup rather than at the first draw if the list above is edited wrongly. */
    static {
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i).num() != i + 1) {
                throw new IllegalStateException("Characters must be numbered from 1 without a gap: see number "
                        + ALL.get(i).num());
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
