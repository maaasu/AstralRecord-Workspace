package io.github.maaasu.astralRecord.feature.skill.gui;

import io.github.maaasu.astralRecord.feature.skill.model.SkillBindInventoryHolder;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindScreen;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillBindGuiLayoutTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 1. スキルマネージャー
     * 検証契約: 通常攻撃を0、前後ページを45/53、presetを46〜48・50〜52、戻る/閉じるを49へ配置する。
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

        assertEquals(0, SkillBindGui.NORMAL_ATTACK_SLOT);
        assertEquals(26, SkillBindGui.CONTENT_SLOT_COUNT);
        assertEquals(45, SkillBindGui.PREVIOUS_PAGE_SLOT);
        assertEquals(49, SkillBindGui.BACK_SLOT);
        assertEquals(53, SkillBindGui.NEXT_PAGE_SLOT);
        assertEquals(-1, SkillBindGui.presetSlot(0));
        assertEquals(-1, SkillBindGui.presetSlot(7));
        assertEquals(-1, SkillBindGui.presetIndexAtSlot(49));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 5. 識別とページング
     * 検証契約: メインの49だけを共有ナビゲーションへ公開し、合成の49はスキルマネージャーへ戻る専用操作にする。
     */
    @Test
    void synthesisBackDoesNotUseSharedNavigationSlot() {
        assertEquals(49, new SkillBindInventoryHolder(SkillBindScreen.MAIN, 1, 0).getBackSlot());
        assertEquals(-1, new SkillBindInventoryHolder(SkillBindScreen.SYNTHESIS, 1, 0, "skill").getBackSlot());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/4-統合フロー/13_4-スキルバインドGUI.md
     * 章・見出し: # 13_4-スキルバインドGUI > ## 2. バインド
     * 検証契約: 通常攻撃はアクティブ・左クリックの設定先では表示するが、設定不能なパッシブ枠の選択中は表示しない。
     */
    @Test
    void normalAttackIsHiddenOnlyWhileSelectingPassiveSlot() {
        assertFalse(SkillBindGui.shouldShowNormalAttack(SkillBindType.PASSIVE));
        assertTrue(SkillBindGui.shouldShowNormalAttack(SkillBindType.ACTIVE));
        assertTrue(SkillBindGui.shouldShowNormalAttack(SkillBindType.LEFT_CLICK));
        assertTrue(SkillBindGui.shouldShowNormalAttack(null));
        assertTrue(SkillBindGui.shouldShowNormalAttack(0, SkillBindType.ACTIVE));
        assertFalse(SkillBindGui.shouldShowNormalAttack(1, SkillBindType.ACTIVE));
        assertFalse(SkillBindGui.shouldShowNormalAttack(1, null));
    }
}
