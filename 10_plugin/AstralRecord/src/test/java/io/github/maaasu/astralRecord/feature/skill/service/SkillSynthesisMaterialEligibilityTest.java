package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemSigil;
import io.github.maaasu.astralRecord.feature.item.model.ItemSkillGem;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillSigil;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillManagerEntry;
import io.github.maaasu.astralRecord.feature.skill.model.SkillSigilSlotDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillSynthesisMaterialEligibilityTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: 同スキルジェムはレベル上限前のみ、許可シジルは空き枠と重複groupなしの場合だけ選択・合成できる。
     */
    @Test
    void classifiesNormalGemAndSigilSynthesisPaths() {
        SkillManagerEntry entry = entry(1, List.of(), List.of("allowed_sigil"));

        assertEquals(SkillSynthesisMaterialEligibility.MaterialKind.GEM,
            SkillSynthesisMaterialEligibility.resolve(entry, gem("MAGE_FIREBALL")));
        assertEquals(SkillSynthesisMaterialEligibility.MaterialKind.SIGIL,
            SkillSynthesisMaterialEligibility.resolve(entry, sigil("ALLOWED_SIGIL", "cooldown")));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: 非許可シジルは素材選択へ進めず、API mutation の前に理由別の拒否表示へ分岐する。
     */
    @Test
    void rejectsUnsupportedSigilBeforeItCanBeSelected() {
        SkillManagerEntry entry = entry(1, List.of(), List.of("allowed_sigil"));

        assertEquals(SkillSynthesisMaterialEligibility.MaterialKind.SIGIL_NOT_ALLOWED,
            SkillSynthesisMaterialEligibility.resolve(entry, sigil("other_sigil", "other_group")));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: 同系統シジルと上限到達ジェムは、選択不可理由を失わずに分類する。
     */
    @Test
    void preservesDuplicateGroupAndMaxLevelRejectionReasons() {
        LearnedSkillSigil attached = new LearnedSkillSigil(UUID.randomUUID(), "allowed_sigil", "cooldown", 0);
        SkillManagerEntry fullEntry = entry(3, List.of(attached), List.of("allowed_sigil"));

        assertEquals(SkillSynthesisMaterialEligibility.MaterialKind.INVALID_GEM,
            SkillSynthesisMaterialEligibility.resolve(fullEntry, gem("mage_fireball")));
        assertEquals(SkillSynthesisMaterialEligibility.MaterialKind.DUPLICATE_SIGIL_GROUP,
            SkillSynthesisMaterialEligibility.resolve(fullEntry, sigil("allowed_sigil", "COOLDOWN")));
    }

    private static SkillManagerEntry entry(int level, List<LearnedSkillSigil> sigils, List<String> allowedSigils) {
        SkillDefinition definition = new SkillDefinition(
            "mage_fireball", "mage_fireball", "火焔弾", null, "FIRE_CHARGE", List.of(),
            60L, 18.0D, 0L, 1, null, java.util.Map.of(), List.of(),
            io.github.maaasu.astralRecord.feature.skill.model.SkillKind.ACTIVE, true,
            io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType.MANA, 18.0D,
            "mage_fireball", 3, List.of(), List.of(new SkillSigilSlotDefinition(1, 1)), allowedSigils
        );
        LearnedSkillInstance learned = new LearnedSkillInstance(
            UUID.randomUUID(), UUID.randomUUID(), "mage_fireball", level, sigils, 1, null, null
        );
        return new SkillManagerEntry(learned, definition, true);
    }

    private static ItemModel gem(String skillId) {
        ItemModel item = mock(ItemModel.class);
        ItemSkillGem gem = mock(ItemSkillGem.class);
        when(item.getSkillGem()).thenReturn(gem);
        when(gem.getSkillId()).thenReturn(skillId);
        return item;
    }

    private static ItemModel sigil(String itemId, String groupId) {
        ItemModel item = mock(ItemModel.class);
        when(item.getId()).thenReturn(itemId);
        when(item.getSigil()).thenReturn(new ItemSigil(groupId, List.of()));
        return item;
    }
}
