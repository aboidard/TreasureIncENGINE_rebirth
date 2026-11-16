package org.bogomips.treasureInc.engine.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * A monster met in an expedition. Its strength comes from where it is met (see {@link #scale}): the level of the
 * location makes it much stronger (8 times at level 2, 60 times at level 3), and each floor below the first makes
 * it {@value #FLOOR_PERCENT} % stronger.
 *
 * @param kind     which monster it is; the client draws it from this id: never rename one
 * @param guardian true for the monster that keeps the chest of a floor: it is stronger, and drops the chest
 * @param interval milliseconds between two attacks
 */
public record Monster(String kind, String name, boolean guardian, int hp, int attack, int defense, int interval) {

    /**
     * How strong a location is, in percent of a location of level 1, by level. The second level asks for a
     * character that gained levels and wears some equipment; the third one for a high level and a good equipment.
     */
    private static final int[] LEVEL_SCALE = {100, 800, 6000};
    static final int FLOOR_PERCENT = 12;
    // the stats of an average monster on the first floor of a location of level 1; attack also grows with the scale
    static final int HP = 26;
    static final int ATTACK = 2;
    static final int ATTACK_PER_SCALE = 4;
    static final int GUARDIAN_HP_PERCENT = 150;
    static final int GUARDIAN_ATTACK_PERCENT = 120;

    /** A kind of monster: its life, attack and defense are in percent of those of an average monster. */
    record Kind(String id, String name, int hp, int attack, int defense, int interval) {
    }

    static final List<Kind> KINDS = List.of(
            new Kind("rat", "Rat des mines", 70, 70, 0, 1200),
            new Kind("bat", "Chauve-souris", 60, 80, 0, 1000),
            new Kind("goblin", "Gobelin chapardeur", 100, 100, 100, 1800),
            new Kind("spider", "Araignée géante", 90, 120, 50, 1500),
            new Kind("slime", "Slime visqueux", 160, 70, 0, 2400),
            new Kind("skeleton", "Squelette", 110, 110, 150, 2000),
            new Kind("mummy", "Momie", 140, 120, 100, 2600),
            new Kind("ghost", "Spectre", 80, 140, 200, 2200),
            new Kind("troll", "Troll des cavernes", 180, 150, 100, 3000),
            new Kind("golem", "Golem de pierre", 200, 130, 300, 3200));

    /**
     * A random monster.
     *
     * @param level the level of the location, from 1
     * @param floor the floor it is met on, from 1
     */
    public static Monster random(int level, int floor, boolean guardian, RandomGenerator random) {
        return of(KINDS.get(random.nextInt(KINDS.size())), level, floor, guardian);
    }

    /**
     * How strong what is met on a floor is, in percent of what is met on the first floor of a location of level 1.
     *
     * @param level the level of the location, from 1
     * @param floor from 1
     */
    public static int scale(int level, int floor) {
        return levelScale(level) * (100 + FLOOR_PERCENT * (Math.max(1, floor) - 1)) / 100;
    }

    /** How strong a location of this level is, in percent of a location of level 1. */
    static int levelScale(int level) {
        int known = Math.min(Math.max(1, level), LEVEL_SCALE.length);
        long scale = LEVEL_SCALE[known - 1];
        // a level above those of the table grows as the last one did
        for (int more = known; more < level; more++) {
            scale = scale * LEVEL_SCALE[LEVEL_SCALE.length - 1] / LEVEL_SCALE[LEVEL_SCALE.length - 2];
        }
        return (int) Math.min(scale, 1_000_000);
    }

    static Monster of(Kind kind, int level, int floor, boolean guardian) {
        int scale = scale(level, floor);
        long hp = (long) HP * scale * kind.hp() * (guardian ? GUARDIAN_HP_PERCENT : 100);
        long attack = (ATTACK * 100L + (long) ATTACK_PER_SCALE * scale) * kind.attack()
                * (guardian ? GUARDIAN_ATTACK_PERCENT : 100);
        return new Monster(kind.id(), guardian ? kind.name() + ", gardien du coffre" : kind.name(), guardian,
                (int) Math.max(1, Math.round(hp / 1_000_000.0)),
                (int) Math.max(1, Math.round(attack / 1_000_000.0)),
                Math.round(scale * kind.defense() / 10_000f),
                kind.interval());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("name", name);
        m.put("hp", hp);
        m.put("attack", attack);
        m.put("defense", defense);
        m.put("interval", interval);
        return m;
    }
}
