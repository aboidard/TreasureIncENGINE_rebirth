package org.bogomips.treasureInc.engine.item;

import org.bogomips.treasureInc.engine.item.Equipment.Kind;
import org.bogomips.treasureInc.engine.item.Equipment.Skill;
import org.bogomips.treasureInc.engine.item.Equipment.Slot;
import org.bogomips.treasureInc.engine.item.ItemCatalog.Rarity;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentTest {

    /** Number of item sprites of the Unity client (engine.items.graphics-count). */
    private static final int GRAPHICS_COUNT = 181;
    private static final Set<String> SKILL_IDS = Set.of("breakable", "luck_charm");

    @Test
    void noItemIsEquipableWhenTheChanceIsZero() {
        ItemCatalog catalog = new ItemCatalog(new Random(1), GRAPHICS_COUNT, 0);
        for (int id = 0; id < 500; id++) {
            ItemInfo item = catalog.generate(id);
            assertNull(item.stats());
            assertFalse(item.equipable());
            assertNull(item.toMap().get("stats"));
        }
    }

    @Test
    void anEquipmentHasTheSlotTheStatAndASpriteOfItsKind() {
        ItemCatalog catalog = new ItemCatalog(new Random(2), GRAPHICS_COUNT, 100);
        Set<Slot> seen = EnumSet.noneOf(Slot.class);
        Set<String> skills = new HashSet<>();
        for (int id = 0; id < 3000; id++) {
            ItemInfo item = catalog.generate(id);
            assertTrue(item.equipable());
            Map<String, Object> stats = item.stats();
            int slot = Equipment.slot(stats);
            Kind kind = kindOf(slot);
            seen.add(kind.slot);
            assertTrue(kind.graphics.contains(item.graphics()), item.graphics() + " is not a sprite of " + kind);
            assertTrue(kind.nouns.stream().anyMatch(n -> item.name().startsWith(n.split(",")[0] + " ")), item.name());

            int value = (Integer) stats.get(kind.stat);
            int[] range = range(Rarity.fromValue(item.rarity()));
            assertTrue(value >= range[0] && value <= range[1], item.rarity() + " " + kind.stat + " " + value);
            for (Map.Entry<String, Object> stat : stats.entrySet()) {
                switch (stat.getKey()) {
                    case "slot", "for", "con", "luk" -> assertTrue((Integer) stat.getValue() >= 1);
                    case "spe1", "spe2" -> {
                        assertTrue(SKILL_IDS.contains(stat.getValue()), "unknown skill " + stat.getValue());
                        skills.add((String) stat.getValue());
                    }
                    default -> fail("unexpected stat " + stat.getKey());
                }
            }
            if (stats.containsKey("spe2")) {
                assertNotEquals(stats.get("spe1"), stats.get("spe2"), "the same skill twice");
            }
            assertSame(stats, item.toMap().get("stats"));
        }
        assertEquals(EnumSet.allOf(Slot.class), seen, "every slot has its equipment");
        assertEquals(SKILL_IDS, skills);
    }

    @Test
    void aboutOneItemOutOfTwentyIsEquipable() {
        ItemCatalog catalog = new ItemCatalog(new Random(3), GRAPHICS_COUNT, 5);
        int equipable = 0;
        for (int id = 0; id < 20000; id++) {
            if (catalog.generate(id).equipable()) {
                equipable++;
            }
        }
        assertTrue(equipable > 800 && equipable < 1200, equipable + " equipable items out of 20000");
    }

    @Test
    void onlyTheSpritesTheClientHasAreUsed() {
        // the rings 0..6 are the only equipment sprites below 7
        ItemCatalog catalog = new ItemCatalog(new Random(4), 7, 100);
        for (int id = 0; id < 200; id++) {
            ItemInfo item = catalog.generate(id);
            assertEquals(Slot.RING.number(), Equipment.slot(item.stats()));
            assertTrue(item.graphics() < 7);
        }
        assertEquals(List.of(Kind.RING), List.copyOf(Equipment.sprites(7).keySet()));
        assertTrue(Equipment.sprites(GRAPHICS_COUNT).keySet().containsAll(EnumSet.allOf(Kind.class)));
    }

    @Test
    void theSlotNumbersAndSkillIdsAreStoredSoTheyNeverChange() {
        assertEquals(1, Slot.HEAD.number());
        assertEquals(2, Slot.ARMOR.number());
        assertEquals(3, Slot.WEAPON.number());
        assertEquals(4, Slot.SHIELD.number());
        assertEquals(5, Slot.BOOTS.number());
        assertEquals(6, Slot.GLOVES.number());
        assertEquals(7, Slot.RING.number());
        assertEquals(8, Slot.AMULET.number());
        assertEquals(9, Slot.TALISMAN.number());
        assertFalse(Slot.isValid(0));
        assertTrue(Slot.isValid(1));
        assertTrue(Slot.isValid(9));
        assertFalse(Slot.isValid(10));
        assertEquals("breakable", Skill.BREAKABLE.id());
        assertEquals("luck_charm", Skill.LUCK_CHARM.id());
    }

    @Test
    void swordsGiveStrengthShieldsAndArmorsConstitution() {
        assertEquals("for", Kind.SWORD.stat);
        assertEquals(Slot.WEAPON, Kind.SWORD.slot);
        assertEquals("con", Kind.SHIELD.stat);
        assertEquals(Slot.SHIELD, Kind.SHIELD.slot);
        assertEquals("con", Kind.ARMOR.stat);
        assertEquals(Slot.ARMOR, Kind.ARMOR.slot);
    }

    @Test
    void readsTheStats() {
        assertNull(Equipment.slot(null));
        assertNull(Equipment.slot(Map.of("for", 3)));
        assertEquals(3, Equipment.slot(Map.of("slot", 3, "for", 3)));
        assertFalse(Equipment.hasSkill(null, Skill.LUCK_CHARM));
        assertFalse(Equipment.hasSkill(Map.of("slot", 7, "spe1", "breakable"), Skill.LUCK_CHARM));
        assertTrue(Equipment.hasSkill(Map.of("slot", 7, "spe1", "breakable", "spe2", "luck_charm"), Skill.LUCK_CHARM));
        // only the "spe" keys are skills
        assertFalse(Equipment.hasSkill(Map.of("slot", 7, "note", "luck_charm"), Skill.LUCK_CHARM));
    }

    private static Kind kindOf(int slot) {
        for (Kind kind : Kind.values()) {
            if (kind.slot.number() == slot) {
                return kind;
            }
        }
        throw new AssertionError("no kind for slot " + slot);
    }

    private static int[] range(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> new int[]{1, 5};
            case UNCOMMON -> new int[]{4, 10};
            case RARE -> new int[]{8, 18};
            case EPIC -> new int[]{15, 30};
            case LEGENDARY -> new int[]{30, 50};
        };
    }
}
