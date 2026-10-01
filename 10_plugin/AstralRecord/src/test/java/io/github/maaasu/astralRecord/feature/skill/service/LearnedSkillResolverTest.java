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
import io.github.maaasu.astralRecord.feature.skill.model.SkillRequiredItemDefinition;
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
     * 検証契約: 各レベルの前レベル差分と有効シジルの補正を累積し、重複groupや不正slotを除外し、表示用テクスチャを保持する。
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
            "adventurer_smash",
            "adventurer_smash",
            "ファイアボール",
            null,
            "PLAYER_HEAD",
            List.of(),
            100L,
            10.0D,
            20L,
            1,
            null,
            Map.of("damage", 2.0D, "damageRatios", List.of(1.15D, 0.90D)),
            List.of(),
            SkillKind.ACTIVE,
            true,
            SkillResourceType.MANA,
            10.0D,
            "fire_magic",
            3,
            List.of(
                new SkillLevelDefinition(2, -10L, -1.0D, -2L,
                    Map.of("damage", 3.0D, "damageRatios[0]", 0.05D),
                    List.of(new SkillStatusModifierDefinition("SKILL_DAMAGE_INCREASE", 5.0D))),
                new SkillLevelDefinition(3, -20L, -2.0D, -3L,
                    Map.of("damage", 4.0D, "damageRatios[1]", 0.05D),
                    List.of(new SkillStatusModifierDefinition("SKILL_DAMAGE_INCREASE", 6.0D)))
            ),
            List.of(new SkillSigilSlotDefinition(1, 1), new SkillSigilSlotDefinition(3, 2)),
            List.of("cooldown_sigil", "cooldown_sigil_ii"),
            List.of(new SkillRequiredItemDefinition("skill_gem_raw", 1)),
            List.of(new SkillRequiredItemDefinition("skill_gem_raw", 2)),
            "head-texture-fixture"
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
        assertEquals("head-texture-fixture", resolved.definition().getIconTexture());
        assertEquals(14.0D, resolved.definition().getResourceCost(), 0.0001D);
        assertEquals(15L, resolved.definition().getCastTimeTicks());
        assertEquals(9.0D, ((Number) resolved.definition().getParams().get("damage")).doubleValue(), 0.0001D);
        List<?> ratios = (List<?>) resolved.definition().getParams().get("damageRatios");
        assertEquals(1.20D, ((Number) ratios.get(0)).doubleValue(), 0.0001D);
        assertEquals(0.95D, ((Number) ratios.get(1)).doubleValue(), 0.0001D);
        assertEquals(21.0D, resolved.statusBonuses().get(StatusType.SKILL_DAMAGE_INCREASE), 0.0001D);
        assertTrue(resolved.hasSigil("cooldown_sigil"));
        assertFalse(resolved.hasSigil("cooldown_sigil_ii"));
        assertEquals(1, resolved.sigilIds().size());
        assertEquals(definition.getLearnRequiredItems(), resolved.definition().getLearnRequiredItems());
        assertEquals(definition.getLevelUpRequiredItems(), resolved.definition().getLevelUpRequiredItems());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体の解決
     * 検証契約: 有効シジル0〜3個はレベル反映後の主消費と副MPへ1〜4倍を適用し、ゼロ消費と元定義を保持する。
     */
    @Test
    void resolveCommonSigilResourceCost() {
        for (SkillResourceType resourceType : SkillResourceType.values()) {
            for (int count = 0; count <= 3; count++) {
                assertCommonSigilResourceCost(resourceType, count, 20.0D, 5.0D, -4.0D);
            }
            assertCommonSigilResourceCost(resourceType, 3, 0.0D, 0.0D, 0.0D);
            assertCommonSigilResourceCost(resourceType, 3, 20.0D, 20.0D, -20.0D);
        }
    }

    /**
     * 共通の消費倍率を固定fixtureで確認します。
     * @param resourceType 主消費の種別
     * @param count 有効シジル数
     * @param resourceCost 基礎主消費
     * @param manaCost 基礎MP消費
     * @param levelDelta 主消費のレベル差分
     */
    private void assertCommonSigilResourceCost(
        SkillResourceType resourceType, int count, double resourceCost, double manaCost, double levelDelta
    ) {
        ItemService itemService = mock(ItemService.class);
        List<String> ids = List.of("fixture_sigil_a", "fixture_sigil_b", "fixture_sigil_c");
        List<LearnedSkillSigil> sigils = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            String id = ids.get(index);
            ItemModel item = mock(ItemModel.class);
            when(item.getSigil()).thenReturn(new ItemSigil(id, List.of()));
            when(itemService.findLoadedById(id)).thenReturn(item);
            sigils.add(new LearnedSkillSigil(UUID.randomUUID(), id, id, index));
        }
        SkillDefinition definition = new SkillDefinition(
            "fixture_skill", "fixture_skill", "共通計算用スキル", null, "PAPER", List.of(),
            0L, manaCost, 0L, 1, null, Map.of(), List.of(), SkillKind.ACTIVE, true,
            resourceType, resourceCost, null, 2,
            List.of(new SkillLevelDefinition(2, 0L, levelDelta, 0L, Map.of(), List.of())),
            List.of(new SkillSigilSlotDefinition(1, 3)), ids
        );
        LearnedSkillInstance learned = new LearnedSkillInstance(
            UUID.randomUUID(), UUID.randomUUID(), definition.getId(), 2, sigils, 1, null, null
        );

        ResolvedLearnedSkill resolved = new LearnedSkillResolver(itemService).resolve(definition, learned);

        assertEquals((resourceCost + levelDelta) * (1 + count), resolved.definition().getResourceCost(), 0.0001D);
        assertEquals(manaCost * (1 + count), resolved.definition().getManaCost(), 0.0001D);
        assertEquals(resourceType, resolved.definition().getResourceType());
        assertEquals(count, resolved.sigilIds().size());
        assertTrue(resolved.statusBonuses().isEmpty());
        assertEquals(resourceCost, definition.getResourceCost(), 0.0001D);
        assertEquals(manaCost, definition.getManaCost(), 0.0001D);
        assertEquals(count, learned.getSigils().size());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック > ### 6.1 アストラルエッジ変更契約
     * 検証契約: アストラルエッジの最大ENG回復率はLv.1の5%へLv.2〜5の各2%を累積し、Lv.5で13%になる。
     */
    @Test
    void resolveAstralEdgeMaxLevelEnergyRecoveryRatio() {
        SkillDefinition definition = new SkillDefinition(
            "adventurer_astral_edge",
            "adventurer_astral_edge",
            "アストラルエッジ",
            null,
            "IRON_SWORD",
            List.of(),
            50L,
            8.0D,
            0L,
            1,
            null,
            Map.of("energyRecoveryRatio", 0.05D),
            List.of("active", "melee", "adventurer"),
            SkillKind.ACTIVE,
            true,
            SkillResourceType.MANA,
            8.0D,
            null,
            5,
            List.of(
                new SkillLevelDefinition(2, 0L, 0.0D, 0L, Map.of("energyRecoveryRatio", 0.02D), List.of()),
                new SkillLevelDefinition(3, 0L, 0.0D, 0L, Map.of("energyRecoveryRatio", 0.02D), List.of()),
                new SkillLevelDefinition(4, 0L, 0.0D, 0L, Map.of("energyRecoveryRatio", 0.02D), List.of()),
                new SkillLevelDefinition(5, 0L, 0.0D, 0L, Map.of("energyRecoveryRatio", 0.02D), List.of())
            ),
            List.of(),
            List.of()
        );
        LearnedSkillInstance learned = new LearnedSkillInstance(
            UUID.randomUUID(),
            UUID.randomUUID(),
            definition.getId(),
            5,
            List.of(),
            1,
            null,
            null
        );

        ResolvedLearnedSkill resolved = new LearnedSkillResolver(mock(ItemService.class)).resolve(definition, learned);

        assertEquals(0.13D, ((Number) resolved.definition().getParams().get("energyRecoveryRatio")).doubleValue(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 26. メイジ フロストボールの実装契約 > ### 26.1 数値・対象・終端
     * 検証契約: フロストボールの基礎40tickへLv.2〜5の各+40tickを累積すると、最大Lv.5の凍結設定値は200tick（10秒）になる。
     */
    @Test
    void resolveFrostBallMaxLevelFreezeDurationFromLevelDeltas() {
        ItemService itemService = mock(ItemService.class);
        SkillDefinition definition = new SkillDefinition(
            "mage_frost_ball",
            "mage_frost_ball",
            "フロストボール",
            null,
            "SNOWBALL",
            List.of(),
            80L,
            4.0D,
            4L,
            1,
            null,
            Map.of("freezeDurationTicks", 40),
            List.of("active", "magic"),
            SkillKind.ACTIVE,
            true,
            SkillResourceType.MANA,
            12.0D,
            null,
            5,
            List.of(
                new SkillLevelDefinition(2, 0L, 0.0D, 0L, Map.of("freezeDurationTicks", 40.0D), List.of()),
                new SkillLevelDefinition(3, 0L, 0.0D, 0L, Map.of("freezeDurationTicks", 40.0D), List.of()),
                new SkillLevelDefinition(4, 0L, 0.0D, 0L, Map.of("freezeDurationTicks", 40.0D), List.of()),
                new SkillLevelDefinition(5, 0L, 0.0D, 0L, Map.of("freezeDurationTicks", 40.0D), List.of())
            ),
            List.of(),
            List.of()
        );
        LearnedSkillInstance learned = new LearnedSkillInstance(
            UUID.randomUUID(),
            UUID.randomUUID(),
            definition.getId(),
            5,
            List.of(),
            1,
            null,
            null
        );

        ResolvedLearnedSkill resolved = new LearnedSkillResolver(itemService).resolve(definition, learned);

        assertEquals(200.0D, ((Number) resolved.definition().getParams().get("freezeDurationTicks")).doubleValue(), 0.0001D);
    }
}
