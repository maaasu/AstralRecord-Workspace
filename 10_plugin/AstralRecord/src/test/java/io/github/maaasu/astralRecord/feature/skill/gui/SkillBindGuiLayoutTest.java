package io.github.maaasu.astralRecord.feature.skill.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillBindGuiLayoutTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 1. スキルマネージャー
     * 検証契約: pageを45、preset 1〜6を46〜51、backを52、closeを53へ配置する。
     */
    @Test
    void mapsSixPresetSlotsAroundBackButton() {
        int[] presetSlots = {46, 47, 48, 49, 50, 51};
        for (int index = 0; index < presetSlots.length; index++) {
            int preset = index + 1;
            int slot = presetSlots[index];
            assertEquals(slot, SkillBindGui.presetSlot(preset));
            assertEquals(preset, SkillBindGui.presetIndexAtSlot(slot));
        }

        assertEquals(45, SkillBindGui.PAGE_SLOT);
        assertEquals(52, SkillBindGui.BACK_SLOT);
        assertEquals(53, SkillBindGui.CLOSE_SLOT);
        assertEquals(-1, SkillBindGui.presetSlot(0));
        assertEquals(-1, SkillBindGui.presetSlot(7));
        assertEquals(-1, SkillBindGui.presetIndexAtSlot(52));
    }
}
