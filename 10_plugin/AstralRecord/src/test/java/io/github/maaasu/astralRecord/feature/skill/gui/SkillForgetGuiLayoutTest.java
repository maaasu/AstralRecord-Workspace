package io.github.maaasu.astralRecord.feature.skill.gui;

import io.github.maaasu.astralRecord.feature.skill.model.SkillForgetInventoryHolder;
import io.github.maaasu.astralRecord.feature.skill.model.SkillForgetScreen;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillForgetGuiLayoutTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 8. スキル忘却 GUI
     * 検証契約: 忘却一覧は45件/ページで、ページ移動だけを持ち、共通戻るスロットを持たない。
     */
    @Test
    void listUsesFullContentAreaWithoutBackButton() {
        assertEquals(45, SkillForgetGui.CONTENT_SLOT_COUNT);
        assertEquals(45, SkillForgetGui.PREVIOUS_PAGE_SLOT);
        assertEquals(53, SkillForgetGui.NEXT_PAGE_SLOT);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 8. スキル忘却 GUI
     * 検証契約: 確認画面は対象個体UUIDと一覧へ戻るページを holder に保持する。
     */
    @Test
    void confirmHolderKeepsTargetAndReturnPage() {
        SkillForgetInventoryHolder holder = new SkillForgetInventoryHolder(
            SkillForgetScreen.CONFIRM,
            2,
            "learned-skill-id"
        );
        assertEquals(SkillForgetScreen.CONFIRM, holder.screen());
        assertEquals(2, holder.pageIndex());
        assertEquals("learned-skill-id", holder.learnedSkillId());
    }
}
