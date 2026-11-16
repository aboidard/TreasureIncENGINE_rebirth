package org.bogomips.treasureInc.engine.expedition;

import org.bogomips.treasureInc.engine.combat.Combat;
import org.bogomips.treasureInc.engine.combat.Fighter;
import org.bogomips.treasureInc.engine.combat.Monster;
import org.bogomips.treasureInc.engine.item.Equipment.Skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * What happens to a character in an expedition, floor after floor. It is decided when the expedition is sent: the
 * client receives it and shows it, it decides nothing.
 * <ul>
 * <li>A floor has a few spots where an obstacle may stand: a barricade, which only takes time to destroy, or a
 * monster, which is fought (see {@link Combat}).</li>
 * <li>The chest at the end of a floor may be kept by a guardian: a stronger monster that drops it when it dies.</li>
 * <li>The character keeps its wounds from a fight to the next. When its life falls to zero the expedition is over:
 * the floors below are never seen.</li>
 * </ul>
 *
 * @param maxHp          the life of the character when it leaves
 * @param floors         the floors the character went on: the last one is where it died, when it did
 * @param floorsCompleted how many floors the character reached the chest of
 * @param dead           true when the character died before the end
 * @param bonusItems     the items its skills add to the chest (see {@link Skill#LOOTER})
 */
record ExpeditionRun(int maxHp, List<Floor> floors, int floorsCompleted, boolean dead, int bonusItems) {

    static final String BARRICADE = "barricade";
    static final String MONSTER = "monster";
    static final String GUARDIAN = "guardian";

    // a barricade: its solidity on the first floor of a location of level 1, and how long it can take, in ms
    static final int BARRICADE_HP = 25;
    static final int BARRICADE_MIN_DURATION = 1000;
    static final int BARRICADE_MAX_DURATION = 20_000;
    static final int REGENERATION_PERCENT = 10;
    static final int LOOTER_CHANCE = 20;

    /**
     * @param level  the level of the location
     * @param floors how many floors the expedition has
     */
    static ExpeditionRun plan(Fighter fighter, int level, int floors, ExpeditionConfig.Obstacles obstacles,
                              RandomGenerator random) {
        List<Floor> done = new ArrayList<>();
        int hp = fighter.maxHp();
        int completed = 0;
        int bonusItems = 0;
        boolean lastStandLeft = true;
        boolean dead = false;
        for (int floor = 1; floor <= floors && !dead; floor++) {
            List<Encounter> encounters = new ArrayList<>();
            for (Spot spot : spots(obstacles, random)) {
                String type = spot.type();
                int position = spot.position();
                if (BARRICADE.equals(type)) {
                    int solidity = BARRICADE_HP * Monster.scale(level, floor) / 100;
                    encounters.add(new Encounter(type, position, solidity, barricadeDuration(fighter, solidity),
                            null, null));
                    continue;
                }
                Monster monster = Monster.random(level, floor, GUARDIAN.equals(type), random);
                Combat.Result fight = Combat.fight(fighter, hp, monster, lastStandLeft, random);
                encounters.add(new Encounter(type, position, monster.hp(), fight.duration(), monster, fight));
                lastStandLeft &= !fight.lastStandUsed();
                hp = fight.hpEnd();
                if (!fight.win()) {
                    dead = true;
                    break;
                }
                if (fighter.has(Skill.LOOTER) && random.nextInt(100) < LOOTER_CHANCE) {
                    bonusItems++;
                }
            }
            int healed = 0;
            if (!dead) {
                completed++;
                if (fighter.has(Skill.REGENERATION)) {
                    healed = Math.min(fighter.maxHp() - hp, Math.round(fighter.maxHp() * REGENERATION_PERCENT / 100f));
                    hp += healed;
                }
            }
            done.add(new Floor(List.copyOf(encounters), healed, hp));
        }
        return new ExpeditionRun(fighter.maxHp(), List.copyOf(done), completed, dead, bonusItems);
    }

    /** How many monsters the character fought, guardians included and whoever won. */
    int monstersFought() {
        return (int) floors.stream().flatMap(f -> f.encounters().stream()).filter(e -> e.monster() != null).count();
    }

    /** What stands on a floor, in the order it is met: the obstacles of its spots, then the guardian of its chest. */
    private static List<Spot> spots(ExpeditionConfig.Obstacles obstacles, RandomGenerator random) {
        List<Spot> taken = new ArrayList<>();
        for (int spot = 1; spot <= obstacles.spots(); spot++) {
            int position = 100 * spot / (obstacles.spots() + 1);
            int roll = random.nextInt(100);
            if (roll < obstacles.barricadeChance()) {
                taken.add(new Spot(BARRICADE, position));
            } else if (roll < obstacles.barricadeChance() + obstacles.monsterChance()) {
                taken.add(new Spot(MONSTER, position));
            }
        }
        if (random.nextInt(100) < obstacles.guardianChance()) {
            taken.add(new Spot(GUARDIAN, 100));
        }
        return taken;
    }

    private record Spot(String type, int position) {
    }

    /** The time the character takes to destroy a barricade, hitting it as it hits a monster without defense. */
    static int barricadeDuration(Fighter fighter, int solidity) {
        long duration = (long) solidity * fighter.interval() / Math.max(1, fighter.attack());
        if (fighter.has(Skill.DEMOLISHER)) {
            duration /= 2;
        }
        return (int) Math.max(BARRICADE_MIN_DURATION, Math.min(BARRICADE_MAX_DURATION, duration));
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxHp", maxHp);
        m.put("floorsCompleted", floorsCompleted);
        m.put("dead", dead);
        m.put("floors", floors.stream().map(Floor::toMap).toList());
        return m;
    }

    /**
     * @param healed the life the character recovered at the end of the floor (see {@link Skill#REGENERATION})
     * @param hpEnd  its life when it leaves the floor: 0 when it died there
     */
    record Floor(List<Encounter> encounters, int healed, int hpEnd) {

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("encounters", encounters.stream().map(Encounter::toMap).toList());
            m.put("healed", healed);
            m.put("hpEnd", hpEnd);
            return m;
        }
    }

    /**
     * @param type     {@link #BARRICADE}, {@link #MONSTER} or {@link #GUARDIAN}
     * @param position where it stands on the way from the start of the floor (0) to its chest (100)
     * @param hp       the solidity of a barricade, the life of a monster
     * @param duration how long the character is stopped, in milliseconds
     * @param monster  null for a barricade
     * @param fight    null for a barricade
     */
    record Encounter(String type, int position, int hp, int duration, Monster monster, Combat.Result fight) {

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("position", position);
            m.put("hp", hp);
            m.put("duration", duration);
            if (monster != null) {
                m.put("monster", monster.toMap());
                m.put("fight", fight.toMap());
            }
            return m;
        }
    }
}
