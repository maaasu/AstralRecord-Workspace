package io.github.maaasu.astralRecord.feature.skill.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillBindGuiLayoutTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 1. bind main GUI
     * 検証契約: preset 1〜3を46〜48、backを49、preset 4〜6を50〜52へ配置する。
     */
    @Test
    void mapsSixPresetSlotsAroundBackButton() {
        int[] presetSlots = {46, 47, 48, 50, 51, 52};
        for (int index = 0; index < presetSlots.length; index++) {
            int preset = index + 1;
            int slot = presetSlots[index];
            assertEquals(slot, SkillBindGui.presetSlot(preset));
            assertEquals(preset, SkillBindGui.presetIndexAtSlot(slot));
        }

        assertEquals(49, SkillBindGui.BACK_SLOT);
        assertEquals(-1, SkillBindGui.presetSlot(0));
        assertEquals(-1, SkillBindGui.presetSlot(7));
        assertEquals(-1, SkillBindGui.presetIndexAtSlot(49));
    }
}
