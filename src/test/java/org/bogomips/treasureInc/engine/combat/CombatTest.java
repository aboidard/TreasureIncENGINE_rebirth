package org.bogomips.treasureInc.engine.combat;

import org.bogomips.treasureInc.engine.combat.Combat.Event;
import org.bogomips.treasureInc.engine.combat.Combat.Result;
import org.bogomips.treasureInc.engine.item.Equipment.Skill;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CombatTest {

    /** A generator whose percent rolls are always the same: 99 never succeeds, 0 always does. */
    private static Random rolling(int percentRoll) {
        return new Random(7) {
            @Override
            public int nextInt(int bound) {
                return bound == 100 ? percentRoll : super.nextInt(bound);
            }
        };
    }

    private static final Random UNLUCKY = rolling(99);

    private static Fighter fighter(Skill... skills) {
        return Fighter.of(7, 5, 5, 0, skills.length == 0 ? Set.of() : EnumSet.copyOf(Arrays.asList(skills)));
    }

    /** Average on everything, attacking every 2 seconds. */
    private static Monster monster(int hp, int attack) {
        return new Monster("goblin", "Gobelin", false, hp, attack, 0, 2000);
    }

    private static List<Event> of(Result result, Skill skill) {
        return result.events().stream().filter(e -> e.skill() == skill).toList();
    }

    // ---- the fighter

    @Test
    void theStatsMakeTheFighter() {
        Fighter none = Fighter.of(0, 0, 0, 0, Set.of());
        assertEquals(60, none.maxHp());
        assertEquals(6, none.attack());
        assertEquals(0, none.defense());
        assertEquals(2000, none.interval());
        assertEquals(5, none.critChance());
        assertEquals(0, none.dodgeChance());

        Fighter some = Fighter.of(10, 5, 20, 30, Set.of());
        assertEquals(100, some.maxHp());
        assertEquals(26, some.attack());
        assertEquals(5, some.defense());
        assertEquals(1000, some.interval(), "20 points of agility: twice as fast");
        assertEquals(20, some.critChance());
        assertEquals(50, Fighter.of(0, 0, 0, 500, Set.of()).critChance(), "the luck has a limit");
        assertEquals(400, Fighter.of(0, 0, 5000, 0, Set.of()).interval(), "and so has the speed");
    }

    @Test
    void thePassiveSkillsLastForTheWholeExpedition() {
        Fighter plain = Fighter.of(10, 5, 20, 30, Set.of());
        assertEquals(120, Fighter.of(10, 5, 20, 30, Set.of(Skill.TOUGH)).maxHp());
        assertEquals(30, Fighter.of(10, 5, 20, 30, Set.of(Skill.SHARP)).attack());
        assertEquals(6, Fighter.of(10, 5, 20, 30, Set.of(Skill.THICK_SKIN)).defense());
        assertEquals(833, Fighter.of(10, 5, 20, 30, Set.of(Skill.SWIFT)).interval());
        assertEquals(35, Fighter.of(10, 5, 20, 30, Set.of(Skill.KEEN_EYE)).critChance());
        assertEquals(15, Fighter.of(10, 5, 20, 30, Set.of(Skill.DODGE)).dodgeChance());
        assertEquals(plain.maxHp(), Fighter.of(10, 5, 20, 30, Set.of(Skill.FINISH_HIM)).maxHp());
    }

    @Test
    void thereAreTwelveActiveAndTenPassiveSkillsWhoseIdsNeverChange() {
        assertEquals(List.of("finish_him", "engagement", "second_wind", "berserk", "riposte", "shield_bash",
                        "double_strike", "ambush", "poison", "last_stand", "war_cry", "drain"),
                Arrays.stream(Skill.values()).filter(s -> s.type() == Skill.Type.ACTIVE).map(Skill::id).toList());
        assertEquals(List.of("tough", "sharp", "swift", "thick_skin", "keen_eye", "demolisher", "regeneration",
                        "thorns", "looter", "dodge"),
                Arrays.stream(Skill.values()).filter(s -> s.type() == Skill.Type.PASSIVE).map(Skill::id).toList());
        assertSame(Skill.FINISH_HIM, Skill.fromId("finish_him"));
        assertNull(Skill.fromId("nothing"));
    }

    // ---- the fight

    @Test
    void theFightGoesOnUntilOneDies() {
        Fighter fighter = fighter();
        Result won = Combat.fight(fighter, fighter.maxHp(), monster(60, 5), true, UNLUCKY);
        assertTrue(won.win());
        assertEquals(fighter.maxHp(), won.hpStart());
        assertTrue(won.hpEnd() > 0 && won.hpEnd() < fighter.maxHp());
        Event last = won.events().get(won.events().size() - 1);
        assertEquals(0, last.monsterHp());
        assertEquals(won.hpEnd(), last.hp());
        assertEquals(won.duration(), last.time());
        // 20 of damage every 1600 ms: 3 hits
        assertEquals(4800, won.duration());

        int time = 0;
        int hp = fighter.maxHp();
        int monsterHp = 60;
        for (Event event : won.events()) {
            assertTrue(event.time() >= time, "the events are in the order of the time");
            time = event.time();
            assertEquals(Event.HIT, event.type());
            if (Combat.CHARACTER.equals(event.by())) {
                monsterHp -= event.amount();
            } else {
                hp -= event.amount();
            }
            assertEquals(hp, event.hp());
            assertEquals(monsterHp, event.monsterHp());
        }

        Result lost = Combat.fight(fighter, 10, monster(500, 30), true, UNLUCKY);
        assertFalse(lost.win());
        assertEquals(10, lost.hpStart());
        assertEquals(0, lost.hpEnd());
        assertEquals(0, lost.events().get(lost.events().size() - 1).hp());
    }

    @Test
    void theDefenseWeakensTheHits() {
        assertEquals(20, Combat.damage(20, 0));
        assertEquals(10, Combat.damage(20, 33.4), 1);
        assertEquals(1, Combat.damage(1, 1000), "a hit always hurts");
    }

    @Test
    void aLuckyHitIsCritical() {
        Result result = Combat.fight(fighter(), 100, monster(60, 5), true, rolling(0));
        Event first = result.events().get(0);
        assertTrue(first.critical());
        assertEquals(30, first.amount());
    }

    @Test
    void aFightThatNeverEndsIsLost() {
        Result result = Combat.fight(Fighter.of(0, 0, 0, 0, Set.of()), 60, new Monster("golem", "Golem", false,
                1_000_000, 1, 0, 100_000), true, UNLUCKY);
        assertFalse(result.win());
        assertEquals(Combat.MAX_DURATION, result.duration());
    }

    // ---- the active skills

    @Test
    void finishHimIsACriticalHitOnAnEnemyAlmostDead() {
        // 20 of damage a hit: after 5 hits the monster has 5 of its 105 points left, less than 10 %
        Result result = Combat.fight(fighter(Skill.FINISH_HIM), 100, monster(105, 1), true, UNLUCKY);
        List<Event> finish = of(result, Skill.FINISH_HIM);
        assertEquals(1, finish.size());
        assertTrue(finish.get(0).critical());
        assertEquals(0, finish.get(0).monsterHp());

        // against a monster with more life left, the hit does twice the damage, once
        Result strong = Combat.fight(fighter(Skill.FINISH_HIM), 100,
                new Monster("golem", "Golem", false, 1000, 1, 0, 2000), true, UNLUCKY);
        finish = of(strong, Skill.FINISH_HIM);
        assertEquals(1, finish.size());
        assertEquals(40, finish.get(0).amount());
        assertTrue(finish.get(0).monsterHp() + 40 < 100, "used below 10 % of 1000");
    }

    @Test
    void engagementRaisesTheDefenseWhenTheFightStarts() {
        Fighter fighter = Fighter.of(0, 20, 0, 0, Set.of(Skill.ENGAGEMENT));
        Result result = Combat.fight(fighter, fighter.maxHp(), monster(10_000, 100), true, UNLUCKY);
        assertEquals(Skill.ENGAGEMENT, result.events().get(0).skill());
        assertEquals(0, result.events().get(0).time());
        List<Event> received = result.events().stream().filter(e -> Combat.MONSTER.equals(e.by())).toList();
        // defense 20, 30 while engaged: 100 / 1.9 then 100 / 1.6
        assertEquals(53, received.get(0).amount());
        assertEquals(53, received.get(1).amount());
        assertEquals(63, received.get(2).amount(), "5 seconds later the defense is back to normal");
    }

    @Test
    void theOpeningSkillsComeFirst() {
        Result result = Combat.fight(fighter(Skill.WAR_CRY, Skill.AMBUSH), 100, monster(1000, 20), true, UNLUCKY);
        assertEquals(Skill.WAR_CRY, result.events().get(0).skill());
        Event ambush = result.events().get(1);
        assertEquals(Skill.AMBUSH, ambush.skill());
        assertEquals(0, ambush.time());
        assertEquals(30, ambush.amount(), "150 % of 20");
        List<Event> received = result.events().stream().filter(e -> Combat.MONSTER.equals(e.by())).toList();
        // defense 5: 20 / 1.15, and 15 / 1.15 for 6 seconds
        assertEquals(13, received.get(0).amount());
        assertEquals(13, received.get(1).amount());
        assertEquals(17, received.get(2).amount());
    }

    @Test
    void aWoundedCharacterUsesItsSkills() {
        Fighter fighter = fighter(Skill.SECOND_WIND, Skill.BERSERK);
        Result result = Combat.fight(fighter, fighter.maxHp(), monster(2000, 30), true, UNLUCKY);
        assertEquals(1, of(result, Skill.BERSERK).size());
        Event berserk = of(result, Skill.BERSERK).get(0);
        assertTrue(berserk.hp() * 2 <= fighter.maxHp());
        List<Event> heals = of(result, Skill.SECOND_WIND);
        assertEquals(1, heals.size());
        assertEquals(Event.HEAL, heals.get(0).type());
        assertEquals(25, heals.get(0).amount(), "a quarter of 100");
        // while berserk, an attack every 1066 ms instead of 1600
        List<Event> hits = result.events().stream()
                .filter(e -> Combat.CHARACTER.equals(e.by()) && e.time() > berserk.time()).toList();
        assertEquals(1000, hits.get(1).time() - hits.get(0).time(), 100);
    }

    @Test
    void theLastStandSavesTheCharacterOnceAnExpedition() {
        Fighter fighter = fighter(Skill.LAST_STAND);
        // the monster dies from the second hit, 1200 ms after the deadly one
        Result saved = Combat.fight(fighter, 10, monster(40, 500), true, UNLUCKY);
        assertTrue(saved.win());
        assertTrue(saved.lastStandUsed());
        assertEquals(1, saved.hpEnd());
        assertEquals(1, of(saved, Skill.LAST_STAND).stream().filter(e -> Event.SKILL.equals(e.type())).count());

        Result dead = Combat.fight(fighter, 10, monster(40, 500), false, UNLUCKY);
        assertFalse(dead.win());
        assertFalse(dead.lastStandUsed());

        Result untouched = Combat.fight(fighter, 100, monster(20, 1), true, UNLUCKY);
        assertFalse(untouched.lastStandUsed());

        // nothing hurts for 2 seconds, then the next hit kills
        Result later = Combat.fight(fighter, 10, monster(1000, 500), true, UNLUCKY);
        assertFalse(later.win());
        assertTrue(later.lastStandUsed());
        assertEquals(4000, later.duration());
    }

    @Test
    void theOtherSkillsAreUsedByThemselves() {
        Fighter fighter = fighter(Skill.DOUBLE_STRIKE, Skill.SHIELD_BASH, Skill.POISON, Skill.DRAIN, Skill.THORNS);
        Result result = Combat.fight(fighter, fighter.maxHp(), monster(2000, 10), true, UNLUCKY);
        assertTrue(result.win());
        // the fourth attack hits twice
        List<Event> attacks = result.events().stream()
                .filter(e -> Combat.CHARACTER.equals(e.by()) && Event.HIT.equals(e.type())
                        && e.skill() != Skill.THORNS && e.skill() != Skill.POISON).toList();
        assertEquals(Skill.DOUBLE_STRIKE, attacks.get(4).skill());
        assertEquals(attacks.get(3).time(), attacks.get(4).time());
        // the bash comes after 3 seconds, then every 8 seconds
        List<Event> bashes = of(result, Skill.SHIELD_BASH);
        assertEquals(3000, bashes.get(0).time());
        assertEquals(11000, bashes.get(1).time());
        // the poison hurts 5 times, a third of the attack each time
        List<Event> poison = of(result, Skill.POISON);
        assertEquals(Event.SKILL, poison.get(0).type());
        for (int i = 1; i <= 5; i++) {
            assertEquals(Event.HIT, poison.get(i).type());
            assertEquals(6, poison.get(i).amount());
            assertEquals(poison.get(0).time() + 1000 * i, poison.get(i).time());
        }
        assertEquals(Event.SKILL, poison.get(6).type());
        assertEquals(poison.get(0).time() + 10_000, poison.get(6).time(), 1600);
        // the drain gives back the damage of its hit
        List<Event> drain = of(result, Skill.DRAIN);
        assertEquals(Event.HIT, drain.get(0).type());
        assertEquals(Event.HEAL, drain.get(1).type());
        assertTrue(drain.get(1).amount() > 0 && drain.get(1).amount() <= drain.get(0).amount());
        // the thorns give back a fifth of each hit received
        assertFalse(of(result, Skill.THORNS).isEmpty());
        of(result, Skill.THORNS).forEach(e -> assertEquals(2, e.amount()));
    }

    @Test
    void theChancesOfDodgeAndRiposte() {
        Result lucky = Combat.fight(fighter(Skill.DODGE, Skill.RIPOSTE), 100, monster(200, 50), true, rolling(0));
        assertTrue(lucky.events().stream().noneMatch(e -> Combat.MONSTER.equals(e.by())), "every hit is dodged");
        assertFalse(of(lucky, Skill.DODGE).isEmpty());
        assertEquals(100, lucky.hpEnd());

        Result unlucky = Combat.fight(fighter(Skill.DODGE, Skill.RIPOSTE), 100, monster(200, 5), true, UNLUCKY);
        assertTrue(of(unlucky, Skill.DODGE).isEmpty());
        assertTrue(of(unlucky, Skill.RIPOSTE).isEmpty());
    }

    // ---- the monsters

    @Test
    void theMonstersGrowWithTheLocationAndTheFloor() {
        Monster.Kind goblin = Monster.KINDS.stream().filter(k -> k.id().equals("goblin")).findFirst().orElseThrow();
        Monster first = Monster.of(goblin, 1, 1, false);
        assertEquals(26, first.hp());
        assertEquals(6, first.attack());
        assertEquals(1, first.defense());
        assertEquals(1800, first.interval());
        assertEquals("Gobelin chapardeur", first.name());
        assertFalse(first.guardian());

        assertEquals(100, Monster.scale(1, 1));
        assertEquals(112, Monster.scale(1, 2));
        assertEquals(800, Monster.scale(2, 1));
        assertEquals(6000, Monster.scale(3, 1));
        assertEquals(12480, Monster.scale(3, 10));
        // a level above the last one known grows as the last one did
        assertEquals(45000, Monster.scale(4, 1));
        Monster deep = Monster.of(goblin, 3, 10, false);
        assertEquals(3245, deep.hp());
        assertEquals(501, deep.attack());

        Monster guardian = Monster.of(goblin, 1, 1, true);
        assertTrue(guardian.guardian());
        assertEquals(39, guardian.hp());
        assertEquals(7, guardian.attack());
        assertTrue(guardian.name().startsWith("Gobelin chapardeur"));

        assertEquals(10, Monster.KINDS.stream().map(Monster.Kind::id).distinct().count());
        Monster random = Monster.random(2, 3, false, new Random(1));
        assertTrue(Monster.KINDS.stream().anyMatch(k -> k.id().equals(random.kind())));
    }
}
