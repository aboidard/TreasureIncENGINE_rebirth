package org.bogomips.treasureInc.engine.item;

import org.bogomips.treasureInc.engine.item.Equipment.Kind;
import org.bogomips.treasureInc.engine.item.Equipment.Skill;
import org.bogomips.treasureInc.engine.item.Equipment.Slot;
import org.bogomips.treasureInc.engine.item.ItemCatalog.Rarity;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentTest {

    /** Number of item sprites of the Unity client (engine.items.graphics-count). */
    private static final int GRAPHICS_COUNT = 325;
    private static final Set<String> SKILL_IDS =
            Arrays.stream(Skill.values()).map(Skill::id).collect(Collectors.toSet());

    @Test
    void noItemIsEquipableWhenTheChanceIsZero() {
        ItemCatalog catalog = new ItemCatalog(new Random(1), GRAPHICS_COUNT, 0);
        for (int id = 0; id < 500; id++) {
            ItemInfo item = catalog.generate(id);
            assertTrue(item.graphics() < Equipment.SHEET_SPRITES, "a skin of equipment on an ordinary item");
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
                    case "slot", "for", "con", "agi", "luk" -> assertTrue((Integer) stat.getValue() >= 1);
                    case "spe1", "spe2" -> {
                        Skill skill = Skill.fromId(stat.getValue());
                        assertNotNull(skill, "unknown skill " + stat.getValue());
                        assertTrue(skill.fits(kind), kind + " cannot carry " + skill);
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
        assertEquals(SKILL_IDS, skills, "every skill can be found on an item");
    }

    @Test
    void aSpriteBelongsToOneKindAndTheHelmetsAreNotArmors() {
        Set<Integer> seen = new HashSet<>();
        for (Kind kind : Kind.values()) {
            for (int sprite : kind.graphics) {
                assertTrue(seen.add(sprite), "sprite " + sprite + " is in two kinds");
            }
        }
        assertEquals(List.of(126, 131), Kind.ARMOR.graphics.stream().filter(s -> s < Equipment.SHEET_SPRITES).toList());
        assertTrue(Kind.HELMET.graphics.containsAll(List.of(130, 133, 134)));
        assertTrue(Kind.BOOTS.graphics.contains(132));
    }

    @Test
    void everyKindHasItsSkinsAfterTheSpritesOfTheSheet() {
        Set<Integer> skins = new HashSet<>();
        for (Kind kind : Kind.values()) {
            List<Integer> drawn = kind.graphics.stream().filter(s -> s >= Equipment.SHEET_SPRITES).toList();
            assertEquals(Equipment.SKINS_PER_KIND, drawn.size(), kind + " skins");
            assertEquals(Equipment.SHEET_SPRITES + kind.ordinal() * Equipment.SKINS_PER_KIND, drawn.get(0));
            assertTrue(kind.graphics.size() >= 15, kind + " has few sprites");
            skins.addAll(drawn);
        }
        // the skins follow each other up to the number of sprites of the client
        assertEquals(GRAPHICS_COUNT - Equipment.SHEET_SPRITES, skins.size());
        assertTrue(skins.stream().allMatch(s -> s < GRAPHICS_COUNT));
        // a client that only has the sheet gets none of them
        assertTrue(Equipment.sprites(Equipment.SHEET_SPRITES).values().stream()
                .allMatch(sprites -> sprites.stream().allMatch(s -> s < Equipment.SHEET_SPRITES)));
    }

    @Test
    void aSkillOnlyComesOnTheItemsThatSuitIt() {
        assertEquals(List.of(Skill.BREAKABLE, Skill.FINISH_HIM, Skill.BERSERK, Skill.RIPOSTE, Skill.DOUBLE_STRIKE,
                Skill.POISON, Skill.DRAIN, Skill.SHARP), Kind.SWORD.skills());
        assertEquals(List.of(Skill.BREAKABLE, Skill.AMBUSH, Skill.SWIFT, Skill.DODGE), Kind.BOOTS.skills());
        assertTrue(Kind.SHIELD.skills().contains(Skill.ENGAGEMENT));
        assertFalse(Kind.SWORD.skills().contains(Skill.LUCK_CHARM));
        for (Kind kind : Kind.values()) {
            assertTrue(kind.skills().size() >= 4, kind + " has few skills");
        }
    }

    @Test
    void aRarerEquipmentHasASkillMoreOften() {
        int before = 0;
        for (Rarity rarity : Rarity.values()) {
            assertTrue(Equipment.skillChance(rarity) > before);
            before = Equipment.skillChance(rarity);
        }
        assertEquals(100, Equipment.skillChance(Rarity.LEGENDARY));
        Random random = new Random(5);
        for (int i = 0; i < 100; i++) {
            assertTrue(Equipment.randomStats(Kind.SWORD, Rarity.LEGENDARY, random).containsKey("spe1"));
        }
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
        assertEquals("for", Equipment.STRENGTH);
        assertEquals("con", Equipment.CONSTITUTION);
        assertEquals("agi", Equipment.AGILITY);
        assertEquals("luk", Equipment.LUCK);
    }

    @Test
    void swordsGiveStrengthShieldsAndArmorsConstitutionBootsAgility() {
        assertEquals("agi", Kind.BOOTS.stat);
        assertEquals(Slot.BOOTS, Kind.BOOTS.slot);
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

        assertEquals(0, Equipment.stat(null, "agi"));
        assertEquals(0, Equipment.stat(Map.of("for", 3), "agi"));
        assertEquals(4, Equipment.stat(Map.of("slot", 5, "agi", 4), "agi"));
        assertEquals(Set.of(), Equipment.skills(null));
        assertEquals(Set.of(Skill.SWIFT, Skill.DODGE),
                Equipment.skills(Map.of("slot", 5, "spe1", "swift", "spe2", "dodge", "spe3", "unknown", "note", "ambush")));
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
