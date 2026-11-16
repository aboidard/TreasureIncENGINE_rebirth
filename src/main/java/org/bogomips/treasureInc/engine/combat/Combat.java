package org.bogomips.treasureInc.engine.combat;

import org.bogomips.treasureInc.engine.item.Equipment.Skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * An automatic fight between a character and a monster, played at once from start to end: the client receives what
 * happened, with the time of each event, and shows it.
 * <p>
 * Each side attacks every "interval" milliseconds. The character uses its active skills by itself, when the
 * circumstances call for them (see {@link Skill}); its passive skills are already in its {@link Fighter}.
 * The time moves by steps of {@value #TICK} ms; a fight that lasts more than {@value #MAX_DURATION} ms is lost.
 */
public final class Combat {

    static final int TICK = 100;
    static final int MAX_DURATION = 120_000;

    static final int CRIT_PERCENT = 150;
    /** Each point of defense makes a hit this much weaker, in percent of the defense-less damage it leaves. */
    static final int DEFENSE_WEIGHT = 3;

    static final int FINISH_HIM_LIFE_PERCENT = 10;
    static final int FINISH_HIM_PERCENT = 200;
    static final int ENGAGEMENT_DEFENSE_PERCENT = 150;
    static final int ENGAGEMENT_DURATION = 5000;
    static final int SECOND_WIND_LIFE_PERCENT = 30;
    static final int SECOND_WIND_HEAL_PERCENT = 25;
    static final int BERSERK_LIFE_PERCENT = 50;
    static final int BERSERK_SPEED_PERCENT = 150;
    static final int BERSERK_DURATION = 6000;
    static final int RIPOSTE_CHANCE = 25;
    static final int SHIELD_BASH_FIRST = 3000;
    static final int SHIELD_BASH_EVERY = 8000;
    static final int SHIELD_BASH_STUN = 1500;
    static final int DOUBLE_STRIKE_EVERY = 4;
    static final int AMBUSH_PERCENT = 150;
    static final int POISON_EVERY = 10_000;
    static final int POISON_DURATION = 5000;
    static final int POISON_TICK = 1000;
    static final int POISON_PERCENT = 30;
    static final int LAST_STAND_DURATION = 2000;
    static final int WAR_CRY_ATTACK_PERCENT = 75;
    static final int WAR_CRY_DURATION = 6000;
    static final int DRAIN_EVERY = 6000;
    static final int THORNS_PERCENT = 20;

    /** The two sides of a fight, as written in the events. */
    public static final String CHARACTER = "c";
    public static final String MONSTER = "m";

    private final Fighter fighter;
    private final Monster monster;
    private final RandomGenerator random;
    private final List<Event> events = new ArrayList<>();

    private int time;
    private int hp;
    private int monsterHp;
    private int nextAttack;
    private int nextMonsterAttack;
    private int attacks;
    private boolean lastStandLeft;
    private boolean lastStandUsed;
    private boolean finishUsed;
    private boolean secondWindUsed;
    private boolean berserkUsed;
    private int engagementUntil;
    private int warCryUntil;
    private int berserkUntil;
    private int untouchableUntil;
    private int nextShieldBash = SHIELD_BASH_FIRST;
    private int nextDrain;
    private int nextPoison;
    private int poisonUntil;
    private int nextPoisonTick;

    private Combat(Fighter fighter, int hp, Monster monster, boolean lastStandLeft, RandomGenerator random) {
        this.fighter = fighter;
        this.monster = monster;
        this.random = random;
        this.hp = Math.max(1, Math.min(fighter.maxHp(), hp));
        this.monsterHp = monster.hp();
        this.lastStandLeft = lastStandLeft && fighter.has(Skill.LAST_STAND);
    }

    /**
     * @param hp            the life the character starts the fight with
     * @param lastStandLeft false when the character already used its last stand in this expedition
     */
    public static Result fight(Fighter fighter, int hp, Monster monster, boolean lastStandLeft, RandomGenerator random) {
        return new Combat(fighter, hp, monster, lastStandLeft, random).run();
    }

    /** The damage of a hit of this attack on this defense: at least 1. */
    static int damage(double attack, double defense) {
        return (int) Math.max(1, Math.round(attack * 100 / (100 + DEFENSE_WEIGHT * defense)));
    }

    private Result run() {
        int startHp = hp;
        start();
        while (hp > 0 && monsterHp > 0 && time < MAX_DURATION) {
            time += TICK;
            poison();
            if (monsterHp > 0 && fighter.has(Skill.SHIELD_BASH) && time >= nextShieldBash) {
                nextShieldBash = time + SHIELD_BASH_EVERY;
                nextMonsterAttack = Math.max(nextMonsterAttack, time) + SHIELD_BASH_STUN;
                skill(Skill.SHIELD_BASH);
            }
            if (monsterHp > 0 && time >= nextAttack) {
                attack();
            }
            if (monsterHp > 0 && time >= nextMonsterAttack) {
                monsterAttack();
            }
            if (hp > 0 && monsterHp > 0) {
                wounded();
            }
        }
        boolean win = monsterHp <= 0 && hp > 0;
        return new Result(win, startHp, win ? hp : 0, time, lastStandUsed, List.copyOf(events));
    }

    private void start() {
        nextAttack = fighter.interval();
        nextMonsterAttack = monster.interval();
        if (fighter.has(Skill.ENGAGEMENT)) {
            engagementUntil = ENGAGEMENT_DURATION;
            skill(Skill.ENGAGEMENT);
        }
        if (fighter.has(Skill.WAR_CRY)) {
            warCryUntil = WAR_CRY_DURATION;
            skill(Skill.WAR_CRY);
        }
        if (fighter.has(Skill.AMBUSH)) {
            hit(AMBUSH_PERCENT, Skill.AMBUSH, false);
        }
    }

    /** The attack of the character, with the skills that change it. */
    private void attack() {
        attacks++;
        nextAttack = time + (time < berserkUntil ? fighter.interval() * 100 / BERSERK_SPEED_PERCENT : fighter.interval());
        if (fighter.has(Skill.FINISH_HIM) && !finishUsed && monsterHp * 100 < monster.hp() * FINISH_HIM_LIFE_PERCENT) {
            finishUsed = true;
            hit(FINISH_HIM_PERCENT, Skill.FINISH_HIM, true);
        } else if (fighter.has(Skill.DRAIN) && time >= nextDrain && hp < fighter.maxHp()) {
            nextDrain = time + DRAIN_EVERY;
            heal(hit(100, Skill.DRAIN, false), Skill.DRAIN);
        } else {
            hit(100, null, false);
        }
        if (monsterHp > 0 && fighter.has(Skill.DOUBLE_STRIKE) && attacks % DOUBLE_STRIKE_EVERY == 0) {
            hit(100, Skill.DOUBLE_STRIKE, false);
        }
        if (monsterHp > 0 && fighter.has(Skill.POISON) && time >= nextPoison) {
            nextPoison = time + POISON_EVERY;
            poisonUntil = time + POISON_DURATION;
            nextPoisonTick = time + POISON_TICK;
            skill(Skill.POISON);
        }
    }

    /**
     * A hit of the character on the monster.
     *
     * @param percent  of the attack of the character; a sure critical hit is not multiplied again
     * @param critical true for a hit that is always critical
     * @return the damage done
     */
    private int hit(int percent, Skill skill, boolean critical) {
        boolean crit = critical || (fighter.critChance() > 0 && random.nextInt(100) < fighter.critChance());
        double attack = fighter.attack() * percent / 100.0 * (crit && !critical ? CRIT_PERCENT / 100.0 : 1);
        int damage = Math.min(monsterHp, damage(attack, monster.defense()));
        monsterHp -= damage;
        events.add(new Event(time, Event.HIT, CHARACTER, skill, damage, crit, hp, monsterHp));
        return damage;
    }

    private void monsterAttack() {
        nextMonsterAttack = time + monster.interval();
        if (time < untouchableUntil) {
            events.add(new Event(time, Event.DODGE, CHARACTER, Skill.LAST_STAND, 0, false, hp, monsterHp));
            return;
        }
        if (fighter.dodgeChance() > 0 && random.nextInt(100) < fighter.dodgeChance()) {
            events.add(new Event(time, Event.DODGE, CHARACTER, Skill.DODGE, 0, false, hp, monsterHp));
            return;
        }
        double attack = monster.attack() * (time < warCryUntil ? WAR_CRY_ATTACK_PERCENT / 100.0 : 1);
        double defense = fighter.defense() * (time < engagementUntil ? ENGAGEMENT_DEFENSE_PERCENT / 100.0 : 1);
        int damage = Math.min(hp, damage(attack, defense));
        hp -= damage;
        boolean saved = hp <= 0 && lastStandLeft;
        if (saved) {
            hp = 1;
        }
        events.add(new Event(time, Event.HIT, MONSTER, null, damage, false, hp, monsterHp));
        if (saved) {
            lastStandLeft = false;
            lastStandUsed = true;
            untouchableUntil = time + LAST_STAND_DURATION;
            skill(Skill.LAST_STAND);
        }
        if (hp <= 0) {
            return;
        }
        if (fighter.has(Skill.THORNS)) {
            int back = Math.min(monsterHp, Math.max(1, Math.round(damage * THORNS_PERCENT / 100f)));
            monsterHp -= back;
            events.add(new Event(time, Event.HIT, CHARACTER, Skill.THORNS, back, false, hp, monsterHp));
        }
        if (monsterHp > 0 && fighter.has(Skill.RIPOSTE) && random.nextInt(100) < RIPOSTE_CHANCE) {
            hit(100, Skill.RIPOSTE, false);
        }
    }

    /** The damage of the poison, each second while it lasts. */
    private void poison() {
        if (time <= poisonUntil && nextPoisonTick > 0 && time >= nextPoisonTick) {
            nextPoisonTick += POISON_TICK;
            int damage = Math.min(monsterHp, Math.max(1, Math.round(fighter.attack() * POISON_PERCENT / 100f)));
            monsterHp -= damage;
            events.add(new Event(time, Event.HIT, CHARACTER, Skill.POISON, damage, false, hp, monsterHp));
        }
    }

    /** The skills of a wounded character. */
    private void wounded() {
        if (fighter.has(Skill.BERSERK) && !berserkUsed && hp * 100 <= fighter.maxHp() * BERSERK_LIFE_PERCENT) {
            berserkUsed = true;
            berserkUntil = time + BERSERK_DURATION;
            skill(Skill.BERSERK);
        }
        if (fighter.has(Skill.SECOND_WIND) && !secondWindUsed
                && hp * 100 <= fighter.maxHp() * SECOND_WIND_LIFE_PERCENT) {
            secondWindUsed = true;
            heal(Math.round(fighter.maxHp() * SECOND_WIND_HEAL_PERCENT / 100f), Skill.SECOND_WIND);
        }
    }

    private void heal(int amount, Skill skill) {
        int healed = Math.min(fighter.maxHp() - hp, Math.max(0, amount));
        hp += healed;
        events.add(new Event(time, Event.HEAL, CHARACTER, skill, healed, false, hp, monsterHp));
    }

    private void skill(Skill skill) {
        events.add(new Event(time, Event.SKILL, CHARACTER, skill, 0, false, hp, monsterHp));
    }

    /**
     * Something that happened in a fight.
     *
     * @param time      milliseconds since the fight started
     * @param type      {@link #HIT}, {@link #HEAL}, {@link #DODGE} or {@link #SKILL} (a skill used, with no hit)
     * @param by        who did it: {@link Combat#CHARACTER} or {@link Combat#MONSTER}
     * @param skill     the skill of the character it comes from; null for a plain hit
     * @param amount    the damage of a hit, the life of a heal
     * @param hp        the life of the character afterwards
     * @param monsterHp the life of the monster afterwards
     */
    public record Event(int time, String type, String by, Skill skill, int amount, boolean critical, int hp,
                        int monsterHp) {

        public static final String HIT = "hit";
        public static final String HEAL = "heal";
        public static final String DODGE = "dodge";
        public static final String SKILL = "skill";

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", time);
            m.put("type", type);
            m.put("by", by);
            if (skill != null) {
                m.put("skill", skill.id());
            }
            if (amount != 0) {
                m.put("amount", amount);
            }
            if (critical) {
                m.put("crit", true);
            }
            m.put("hp", hp);
            m.put("monsterHp", monsterHp);
            return m;
        }
    }

    /**
     * @param win           false when the character died, or when the fight lasted too long
     * @param hpStart       the life of the character when the fight started
     * @param hpEnd         its life at the end: 0 when the fight is lost
     * @param duration      milliseconds
     * @param lastStandUsed true when the character used its last stand in this fight
     */
    public record Result(boolean win, int hpStart, int hpEnd, int duration, boolean lastStandUsed, List<Event> events) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("win", win);
            m.put("duration", duration);
            m.put("hpStart", hpStart);
            m.put("hpEnd", hpEnd);
            m.put("events", events.stream().map(Event::toMap).toList());
            return m;
        }
    }
}
