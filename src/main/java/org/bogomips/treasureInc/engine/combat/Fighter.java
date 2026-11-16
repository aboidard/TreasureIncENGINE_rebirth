package org.bogomips.treasureInc.engine.combat;

import org.bogomips.treasureInc.engine.item.Equipment.Skill;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A character as it fights: what its stats (its own at its level, plus those of its equipment) and its passive
 * skills make of it for a whole expedition.
 * <ul>
 * <li>constitution gives life ({@value #BASE_HP} + {@value #HP_PER_CONSTITUTION} a point) and defense (1 a point);</li>
 * <li>strength gives attack ({@value #BASE_ATTACK} + {@value #ATTACK_PER_STRENGTH} a point);</li>
 * <li>agility gives speed: {@value #BASE_INTERVAL} ms between two attacks, divided by 1 + 5 % a point;</li>
 * <li>luck gives critical hits: {@value #BASE_CRIT} % + 1 point for 2 of luck, {@value #MAX_CRIT} % at most.</li>
 * </ul>
 *
 * @param maxHp       the life the character starts an expedition with
 * @param attack      damage of a hit, before the defense of the enemy
 * @param defense     each point makes the hits received 3 % weaker (see {@link Combat#damage})
 * @param interval    milliseconds between two attacks
 * @param critChance  chance, in percent, that a hit is critical (150 % of the damage)
 * @param dodgeChance chance, in percent, to dodge a hit
 * @param skills      every skill of the character and of its equipment
 */
public record Fighter(int maxHp, int attack, int defense, int interval, int critChance, int dodgeChance,
                      Set<Skill> skills) {

    static final int BASE_HP = 60;
    static final int HP_PER_CONSTITUTION = 8;
    static final int BASE_ATTACK = 6;
    static final int ATTACK_PER_STRENGTH = 2;
    static final int BASE_INTERVAL = 2000;
    static final int MIN_INTERVAL = 400;
    static final int BASE_CRIT = 5;
    static final int MAX_CRIT = 50;

    static final int TOUGH_HP_PERCENT = 20;
    static final int SHARP_ATTACK_PERCENT = 15;
    static final int SWIFT_SPEED_PERCENT = 20;
    static final int THICK_SKIN_DEFENSE_PERCENT = 25;
    static final int KEEN_EYE_CRIT = 15;
    static final int DODGE_CHANCE = 15;

    public static Fighter of(int strength, int constitution, int agility, int luck, Set<Skill> skills) {
        Set<Skill> all = skills.isEmpty() ? EnumSet.noneOf(Skill.class) : EnumSet.copyOf(skills);
        int hp = BASE_HP + HP_PER_CONSTITUTION * Math.max(0, constitution);
        int attack = BASE_ATTACK + ATTACK_PER_STRENGTH * Math.max(0, strength);
        int defense = Math.max(0, constitution);
        // speed is 100 + 5 a point of agility, in percent of the base speed
        int speed = 100 + 5 * Math.max(0, agility);
        int crit = Math.min(MAX_CRIT, BASE_CRIT + Math.max(0, luck) / 2);
        if (all.contains(Skill.TOUGH)) {
            hp = percent(hp, 100 + TOUGH_HP_PERCENT);
        }
        if (all.contains(Skill.SHARP)) {
            attack = percent(attack, 100 + SHARP_ATTACK_PERCENT);
        }
        if (all.contains(Skill.THICK_SKIN)) {
            defense = percent(defense, 100 + THICK_SKIN_DEFENSE_PERCENT);
        }
        if (all.contains(Skill.SWIFT)) {
            speed = percent(speed, 100 + SWIFT_SPEED_PERCENT);
        }
        if (all.contains(Skill.KEEN_EYE)) {
            crit += KEEN_EYE_CRIT;
        }
        int interval = Math.max(MIN_INTERVAL, Math.round(BASE_INTERVAL * 100f / speed));
        return new Fighter(hp, attack, defense, interval, crit, all.contains(Skill.DODGE) ? DODGE_CHANCE : 0,
                Set.copyOf(all));
    }

    public boolean has(Skill skill) {
        return skills.contains(skill);
    }

    /** What the client shows of a character: "hp", "attack", "defense", "interval" (ms), "crit" and "dodge" (%). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hp", maxHp);
        m.put("attack", attack);
        m.put("defense", defense);
        m.put("interval", interval);
        m.put("crit", critChance);
        m.put("dodge", dodgeChance);
        return m;
    }

    private static int percent(int value, int percent) {
        return Math.round(value * percent / 100f);
    }
}
