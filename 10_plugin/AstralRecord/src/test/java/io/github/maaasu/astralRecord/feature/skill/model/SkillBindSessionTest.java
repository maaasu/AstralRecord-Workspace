package io.github.maaasu.astralRecord.feature.skill.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/4-統合フロー/13_4-スキルバインドGUI.md
     * 章・見出し: # 13_4-スキルバインドGUI > ## 2. バインド
     * 検証契約: 未選択のパッシブスキルは有効なパッシブ空き枠だけへ自動設定し、無効枠には設定しない。
     */
    @Test
    void assignsPassiveSkillOnlyToAnEnabledPassiveSlot() {
        SkillBindSession session = new SkillBindSession(List.of(new SkillBindPreset(
            null,
            UUID.randomUUID(),
            1,
            List.of(),
            null,
            Arrays.asList("passive-1", null, null),
            true,
            true,
            1
        )));

        assertTrue(session.assignSelectedOrNextSlot("passive-2", SkillKind.PASSIVE, 2));
        assertEquals("passive-2", session.passiveDraft().get(1));
        assertFalse(session.assignSelectedOrNextSlot("passive-3", SkillKind.PASSIVE, 2));
        assertNull(session.passiveDraft().get(2));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/4-統合フロー/13_4-スキルバインドGUI.md
     * 章・見出し: # 13_4-スキルバインドGUI > ## 2. バインド
     * 検証契約: バインド不要パッシブと、同一プリセットのパッシブ枠へ設定済みの個体は、パッシブ枠へ自動設定しない。
     */
    @Test
    void rejectsUnrequiredOrAlreadyBoundPassiveSkill() {
        UUID boundPassiveId = UUID.randomUUID();
        SkillBindSession session = new SkillBindSession(List.of(new SkillBindPreset(
            null,
            UUID.randomUUID(),
            1,
            List.of(),
            null,
            Arrays.asList(boundPassiveId.toString().toUpperCase(Locale.ROOT), null, null),
            true,
            true,
            1
        )));

        assertFalse(session.assignSelectedOrNextSlot(
            "unrequired-passive", SkillKind.PASSIVE, 5, false));
        assertFalse(session.assignSelectedOrNextSlot(
            boundPassiveId.toString(), SkillKind.PASSIVE, 5, true));
        assertNull(session.passiveDraft().get(1));

        session.selectBindSlot(SkillBindType.PASSIVE, 1);
        assertFalse(session.assignSelectedOrNextSlot(
            "unrequired-passive", SkillKind.PASSIVE, 5, false));
    }
}
