package io.github.maaasu.astralRecord.feature.skill.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillBindGuiLayoutTest {
    @Test
    void mapsSixPresetSlotsAroundBackButton() {
        assertEquals(46, SkillBindGui.presetSlot(1));
        assertEquals(48, SkillBindGui.presetSlot(3));
        assertEquals(50, SkillBindGui.presetSlot(4));
        assertEquals(52, SkillBindGui.presetSlot(6));
        assertEquals(-1, SkillBindGui.presetSlot(7));

        assertEquals(1, SkillBindGui.presetIndexAtSlot(46));
        assertEquals(3, SkillBindGui.presetIndexAtSlot(48));
        assertEquals(4, SkillBindGui.presetIndexAtSlot(50));
        assertEquals(6, SkillBindGui.presetIndexAtSlot(52));
        assertEquals(-1, SkillBindGui.presetIndexAtSlot(SkillBindGui.BACK_SLOT));
    }
}
