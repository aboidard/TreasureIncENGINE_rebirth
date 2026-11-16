package org.bogomips.treasureInc.engine.expedition;

import org.bogomips.treasureInc.engine.combat.Fighter;
import org.bogomips.treasureInc.engine.expedition.ExpeditionRun.Encounter;
import org.bogomips.treasureInc.engine.expedition.ExpeditionRun.Floor;
import org.bogomips.treasureInc.engine.item.Equipment.Skill;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.bogomips.treasureInc.engine.expedition.ExpeditionRulesTest.obstacles;
import static org.junit.jupiter.api.Assertions.*;

class ExpeditionRunTest {

    /** Kentucky Johanes at level 1, wearing nothing. */
    private static final Fighter BEGINNER = Fighter.of(4, 3, 3, 3, Set.of());
    private static final Fighter HERO = Fighter.of(200, 200, 50, 50, Set.of());

    @Test
    void nothingStandsInTheWayWhenTheChancesAreZero() {
        ExpeditionRun run = ExpeditionRun.plan(BEGINNER, 3, 10, obstacles(2, 0, 0, 0), new Random(1));
        assertEquals(10, run.floorsCompleted());
        assertFalse(run.dead());
        assertEquals(BEGINNER.maxHp(), run.maxHp());
        assertEquals(10, run.floors().size());
        for (Floor floor : run.floors()) {
            assertTrue(floor.encounters().isEmpty());
            assertEquals(BEGINNER.maxHp(), floor.hpEnd());
        }
    }

    @Test
    void aBarricadeOnlyTakesTime() {
        ExpeditionRun run = ExpeditionRun.plan(BEGINNER, 3, 10, obstacles(2, 100, 0, 0), new Random(1));
        assertEquals(10, run.floorsCompleted());
        int before = 0;
        for (Floor floor : run.floors()) {
            assertEquals(2, floor.encounters().size());
            assertEquals(33, floor.encounters().get(0).position());
            assertEquals(66, floor.encounters().get(1).position());
            for (Encounter barricade : floor.encounters()) {
                assertEquals(ExpeditionRun.BARRICADE, barricade.type());
                assertNull(barricade.monster());
                assertNull(barricade.fight());
                assertTrue(barricade.duration() >= 1000 && barricade.duration() <= 20_000);
                assertFalse(barricade.toMap().containsKey("fight"));
            }
            assertTrue(floor.encounters().get(0).duration() >= before, "a deeper barricade is more solid");
            before = floor.encounters().get(0).duration();
            assertEquals(BEGINNER.maxHp(), floor.hpEnd(), "a barricade does not hurt");
        }
        // 25 of solidity, 14 of attack every 1739 ms
        assertEquals(3105, ExpeditionRun.barricadeDuration(BEGINNER, 25));
        assertEquals(1552, ExpeditionRun.barricadeDuration(Fighter.of(4, 3, 3, 3, Set.of(Skill.DEMOLISHER)), 25));
        assertEquals(1000, ExpeditionRun.barricadeDuration(HERO, 25));
        assertEquals(20_000, ExpeditionRun.barricadeDuration(BEGINNER, 100_000));
    }

    @Test
    void theWoundsAreKeptFromAFightToTheNext() {
        ExpeditionRun run = ExpeditionRun.plan(HERO, 1, 5, obstacles(2, 0, 100, 100), new Random(3));
        assertEquals(5, run.floorsCompleted());
        assertFalse(run.dead());
        int hp = HERO.maxHp();
        for (Floor floor : run.floors()) {
            assertEquals(List.of(ExpeditionRun.MONSTER, ExpeditionRun.MONSTER, ExpeditionRun.GUARDIAN),
                    floor.encounters().stream().map(Encounter::type).toList());
            assertEquals(100, floor.encounters().get(2).position());
            assertTrue(floor.encounters().get(2).monster().guardian());
            for (Encounter fight : floor.encounters()) {
                assertEquals(hp, fight.fight().hpStart());
                assertTrue(fight.fight().win());
                assertEquals(fight.fight().duration(), fight.duration());
                assertEquals(fight.monster().hp(), fight.hp());
                hp = fight.fight().hpEnd();
            }
            assertEquals(hp, floor.hpEnd());
        }
    }

    @Test
    void theExpeditionStopsWhereTheCharacterDies() {
        Fighter weak = Fighter.of(0, 0, 0, 0, Set.of());
        ExpeditionRun run = ExpeditionRun.plan(weak, 3, 10, obstacles(2, 0, 100, 100), new Random(5));
        assertTrue(run.dead());
        assertTrue(run.floorsCompleted() < 10);
        assertEquals(run.floorsCompleted() + 1, run.floors().size(), "the last floor is where it died");
        Floor last = run.floors().get(run.floors().size() - 1);
        assertEquals(0, last.hpEnd());
        Encounter fatal = last.encounters().get(last.encounters().size() - 1);
        assertFalse(fatal.fight().win());
        assertEquals(0, fatal.fight().hpEnd());
        for (Floor floor : run.floors().subList(0, run.floorsCompleted())) {
            assertTrue(floor.hpEnd() > 0);
        }

        Map<String, Object> map = run.toMap();
        assertEquals(true, map.get("dead"));
        assertEquals(run.floorsCompleted(), map.get("floorsCompleted"));
        assertEquals(run.floors().size(), ((List<?>) map.get("floors")).size());
    }

    @Test
    void regenerationHealsAtTheEndOfEachFloor() {
        Fighter fighter = Fighter.of(50, 20, 10, 0, Set.of(Skill.REGENERATION));
        ExpeditionRun run = ExpeditionRun.plan(fighter, 1, 3, obstacles(1, 0, 100, 0), new Random(2));
        assertFalse(run.dead());
        int hp = fighter.maxHp();
        for (Floor floor : run.floors()) {
            int afterFight = floor.encounters().get(0).fight().hpEnd();
            assertEquals(hp, floor.encounters().get(0).fight().hpStart());
            assertEquals(Math.min(22, fighter.maxHp() - afterFight), floor.healed(), "a tenth of 220, at most");
            assertEquals(afterFight + floor.healed(), floor.hpEnd());
            hp = floor.hpEnd();
        }
    }

    @Test
    void aLooterFindsMoreItems() {
        Random lucky = new Random(4) {
            @Override
            public int nextInt(int bound) {
                return bound == 100 ? 0 : super.nextInt(bound);
            }
        };
        Fighter looter = Fighter.of(200, 200, 50, 0, Set.of(Skill.LOOTER));
        assertEquals(4, ExpeditionRun.plan(looter, 1, 2, obstacles(1, 0, 100, 100), lucky).bonusItems());
        assertEquals(0, ExpeditionRun.plan(HERO, 1, 2, obstacles(1, 0, 100, 100), lucky).bonusItems());
    }

    @Test
    void aBeginnerComesBackFromAShortExpeditionButRarelyFromALongOne() {
        Random random = new Random(42);
        int shortOnes = 0;
        int longOnes = 0;
        for (int i = 0; i < 500; i++) {
            if (!ExpeditionRun.plan(BEGINNER, 1, 2, ExpeditionRulesTest.OBSTACLES, random).dead()) {
                shortOnes++;
            }
            if (!ExpeditionRun.plan(BEGINNER, 1, 10, ExpeditionRulesTest.OBSTACLES, random).dead()) {
                longOnes++;
            }
        }
        assertTrue(shortOnes > 480, shortOnes + " short expeditions out of 500");
        assertTrue(longOnes < 150, longOnes + " long expeditions out of 500");
    }
}
