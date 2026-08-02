package io.github.maaasu.astralRecord.feature.skill.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillBindSessionTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/4-統合フロー/13_4-スキルバインドGUI.md
     * 章・見出し: # 13_4-スキルバインドGUI > ## 2. バインド
     * 検証契約: action ring 6枠が設定済みで左クリックバインドが空の場合、未選択の発動スキルは左クリックバインドへ設定される。
     */
    @Test
    void assignsActiveSkillToUnassignedLeftClickAfterActionRingIsFull() {
        SkillBindSession session = new SkillBindSession(List.of(new SkillBindPreset(
            null,
            UUID.randomUUID(),
            1,
            List.of("active-1", "active-2", "active-3", "active-4", "active-5", "active-6"),
            null,
            List.of(),
            true,
            true,
            1
        )));

        assertTrue(session.assignSelectedOrNextSlot("active-7", SkillKind.ACTIVE));

        assertEquals("active-7", session.leftClickDraft());
    }
}
