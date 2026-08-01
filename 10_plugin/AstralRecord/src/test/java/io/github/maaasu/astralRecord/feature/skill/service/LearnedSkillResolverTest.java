package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemSigil;
import io.github.maaasu.astralRecord.feature.item.model.ItemSigilModifier;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillSigil;
import io.github.maaasu.astralRecord.feature.skill.model.ResolvedLearnedSkill;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillLevelDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.model.SkillSigilSlotDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillStatusModifierDefinition;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LearnedSkillResolverTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体の解決
     * 検証契約: 各レベルの前レベル差分と有効シジルの補正を累積し、重複groupや不正slotを除外する。
     */
    @Test
    void resolveAccumulatesLevelDeltasAndOnlyEffectiveSigils() {
        ItemService itemService = mock(ItemService.class);
        ItemModel cooldownItem = mock(ItemModel.class);
        ItemModel duplicateGroupItem = mock(ItemModel.class);
        when(cooldownItem.getSigil()).thenReturn(new ItemSigil(
            "cooldown",
            List.of(new ItemSigilModifier("SKILL_DAMAGE_INCREASE", 10.0D))
        ));
        when(duplicateGroupItem.getSigil()).thenReturn(new ItemSigil(
            "cooldown",
            List.of(new ItemSigilModifier("SKILL_DAMAGE_INCREASE", 99.0D))
        ));
        when(itemService.findLoadedById("cooldown_sigil")).thenReturn(cooldownItem);
        when(itemService.findLoadedById("cooldown_sigil_ii")).thenReturn(duplicateGroupItem);

        SkillDefinition definition = new SkillDefinition(
            "mage_fireball",
            "mage_fireball",
            "ファイアボール",
            null,
            "FIRE_CHARGE",
            List.of(),
            100L,
            10.0D,
            20L,
            1,
            null,
            Map.of("damage", 2.0D),
            List.of(),
            SkillKind.ACTIVE,
            true,
            SkillResourceType.MANA,
            10.0D,
            "fire_magic",
            3,
            List.of(
                new SkillLevelDefinition(2, -10L, -1.0D, -2L, Map.of("damage", 3.0D),
                    List.of(new SkillStatusModifierDefinition("SKILL_DAMAGE_INCREASE", 5.0D))),
                new SkillLevelDefinition(3, -20L, -2.0D, -3L, Map.of("damage", 4.0D),
                    List.of(new SkillStatusModifierDefinition("SKILL_DAMAGE_INCREASE", 6.0D)))
            ),
            List.of(new SkillSigilSlotDefinition(1, 1), new SkillSigilSlotDefinition(3, 2)),
            List.of("cooldown_sigil", "cooldown_sigil_ii")
        );
        UUID accountId = UUID.randomUUID();
        LearnedSkillInstance learned = new LearnedSkillInstance(
            UUID.randomUUID(),
            accountId,
            definition.getId(),
            3,
            List.of(
                new LearnedSkillSigil(UUID.randomUUID(), "cooldown_sigil", "cooldown", 0),
                new LearnedSkillSigil(UUID.randomUUID(), "cooldown_sigil_ii", "cooldown", 1),
                new LearnedSkillSigil(UUID.randomUUID(), "cooldown_sigil", "cooldown", 2)
            ),
            1,
            null,
            null
        );

        ResolvedLearnedSkill resolved = new LearnedSkillResolver(itemService).resolve(definition, learned);

        assertEquals(70L, resolved.definition().getCooldownTicks());
        assertEquals(7.0D, resolved.definition().getResourceCost(), 0.0001D);
        assertEquals(15L, resolved.definition().getCastTimeTicks());
        assertEquals(9.0D, ((Number) resolved.definition().getParams().get("damage")).doubleValue(), 0.0001D);
        assertEquals(21.0D, resolved.statusBonuses().get(StatusType.SKILL_DAMAGE_INCREASE), 0.0001D);
        assertTrue(resolved.hasSigil("cooldown_sigil"));
        assertFalse(resolved.hasSigil("cooldown_sigil_ii"));
        assertEquals(1, resolved.sigilIds().size());
    }
}
