package io.github.maaasu.astralRecord.feature.skill.active;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillCombatService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillMovementService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillProjectileService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTaskService;
import io.github.maaasu.astralRecord.feature.skill.active.service.TemporarySkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.ActiveSkillExecutorCatalog;
import io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer.AdventurerAstralEdgeExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer.AdventurerBlastArrowExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer.AdventurerLightningBoltExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer.AdventurerManaBurstExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman.SwordsmanShieldDrainExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman.SwordsmanFlameRushExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman.SwordsmanChallengingRoarExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ActiveSkillExecutorDesignTest {

    private static final Set<String> EXPECTED_SKILL_IDS = Set.of(
        "adventurer_astral_edge",
        "adventurer_blast_arrow",
        "adventurer_mana_burst",
        "adventurer_smash",
        "adventurer_quick_shot",
        "adventurer_lightning_bolt",
        "swordsman_shield_drain",
        "swordsman_flame_rush",
        "swordsman_challenging_roar"
    );

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック
     * 検証契約: catalogが設計記載9 skill IDを各1回だけ返し全てPlayerActiveSkillExecutorである。
     */
    @Test
    void catalogContainsEveryDesignedSkillIdExactlyOnce() {
        List<SkillExecutor> executors = ActiveSkillExecutorCatalog.create(
            activeSkillServices()
        );
        Set<String> implementationIds = executors.stream()
            .map(SkillExecutor::implementationId)
            .collect(Collectors.toSet());

        assertEquals(EXPECTED_SKILL_IDS.size(), executors.size());
        assertEquals(executors.size(), implementationIds.size(), "implementation IDs must be unique");
        assertEquals(EXPECTED_SKILL_IDS, implementationIds);
        assertTrue(executors.stream().allMatch(PlayerActiveSkillExecutor.class::isInstance));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_1-モデル定義.md
     * 章・見出し: # 13_1-モデル定義 > ## 3. 解決済みスキル
     * 検証契約: アストラルエッジは実行と表示で共有する射程・対象数・倍率配列を検証し、不備のある定義を拒否する。
     */
    @Test
    void astralEdgeValidatesDataDrivenParams() {
        AdventurerAstralEdgeExecutor executor = new AdventurerAstralEdgeExecutor(activeSkillServices());

        assertDoesNotThrow(() -> executor.validateParams(astralEdgeDefinition(Map.of(
                "reach", 5.5D,
                "maxTargets", 5,
                "damageRatios", List.of(1.0D, 0.5D)
        ))));

        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(astralEdgeDefinition(Map.of(
                        "reach", 5.5D,
                        "maxTargets", 5,
                        "damageRatios", List.of(1.0D)
                )))
        );
        assertEquals("damageRatios", exception.key());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック
     * 検証契約: ブラストアローは射程・半径・倍率・飛翔体仕様と最大対象数をマスタparamsで正しく要求する。
     */
    @Test
    void blastArrowValidatesDataDrivenParams() {
        AdventurerBlastArrowExecutor executor = new AdventurerBlastArrowExecutor(activeSkillServices());

        assertDoesNotThrow(() -> executor.validateParams(blastArrowDefinition(Map.of(
                "range", 14.0D,
                "radius", 2.25D,
                "damageRatio", 1.20D,
                "maxTargets", 6,
                "projectileSpeed", 1.35D,
                "projectileHitRadius", 0.45D
        ))));

        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(blastArrowDefinition(Map.of(
                        "range", 14.0D,
                        "radius", 2.25D,
                        "damageRatio", 1.20D,
                        "maxTargets", 0,
                        "projectileSpeed", 1.35D,
                        "projectileHitRadius", 0.45D
                )))
        );
        assertEquals("maxTargets", exception.key());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 8. 冒険者ライトニングボルトの実装契約
     * 検証契約: ライトニングボルトは主倍率・連鎖半径・連鎖倍率・最大対象数・飛翔体値を正数として要求し、最大連鎖数0を拒否する。
     */
    @Test
    void lightningBoltValidatesDataDrivenParams() {
        AdventurerLightningBoltExecutor executor = new AdventurerLightningBoltExecutor(activeSkillServices());

        assertDoesNotThrow(() -> executor.validateParams(lightningBoltDefinition(Map.of(
                "range", 14.0D,
                "damageRatio", 1.45D,
                "chainRadius", 5.0D,
                "chainDamageRatio", 0.40D,
                "maxChainTargets", 2,
                "projectileSpeed", 2.8D,
                "projectileHitRadius", 0.45D
        ))));

        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(lightningBoltDefinition(Map.of(
                        "range", 14.0D,
                        "damageRatio", 1.45D,
                        "chainRadius", 5.0D,
                        "chainDamageRatio", 0.40D,
                        "maxChainTargets", 0,
                        "projectileSpeed", 2.8D,
                        "projectileHitRadius", 0.45D
                )))
        );
        assertEquals("maxChainTargets", exception.key());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 9. 冒険者マナバーストの実装契約 > ### 9.1 数値と対象形状
     * 検証契約: マナバーストは正の射程・倍率、180度以下の前方扇形、1体以上の最大対象数を必須とする。
     */
    @Test
    void manaBurstValidatesForwardConeParams() {
        AdventurerManaBurstExecutor executor = new AdventurerManaBurstExecutor(activeSkillServices());

        assertDoesNotThrow(() -> executor.validateParams(manaBurstDefinition(Map.of(
                "range", 7.0D,
                "angle", 60.0D,
                "damageRatio", 1.10D,
                "maxTargets", 6
        ))));

        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(manaBurstDefinition(Map.of(
                        "range", 7.0D,
                        "angle", 360.0D,
                        "damageRatio", 1.10D,
                        "maxTargets", 6
                )))
        );
        assertEquals("angle", exception.key());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 10. シールドドレインの実装契約 > ### 10.1 数値・対象・演出
     * 検証契約: シールドドレインは正の射程・対象角・ダメージ・Shield倍率と、0より大きく1以下の吸収率を必須とする。
     */
    @Test
    void shieldDrainValidatesCombatParams() {
        SwordsmanShieldDrainExecutor executor = new SwordsmanShieldDrainExecutor(activeSkillServices());

        assertDoesNotThrow(() -> executor.validateParams(shieldDrainDefinition(Map.of(
                "range", 6.0D,
                "targetAngle", 40.0D,
                "damageRatio", 0.65D,
                "shieldBreakMultiplier", 3.0D,
                "shieldAbsorbRatio", 0.50D,
                "fullShieldDamageBonus", 1.0D
        ))));

        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(shieldDrainDefinition(Map.of(
                        "range", 6.0D,
                        "targetAngle", 40.0D,
                        "damageRatio", 0.65D,
                        "shieldBreakMultiplier", 3.0D,
                        "shieldAbsorbRatio", 1.5D,
                        "fullShieldDamageBonus", 1.0D
                )))
        );
        assertEquals("shieldAbsorbRatio", exception.key());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 11. フレイムラッシュの実装契約
     * 検証契約: フレイムラッシュは正しい前方範囲・2撃倍率・炎上条件を要求し、範囲外の炎上解放レベルを拒否する。
     */
    @Test
    void flameRushValidatesTwoHitAndBurningParams() {
        SwordsmanFlameRushExecutor executor = new SwordsmanFlameRushExecutor(activeSkillServices());

        assertDoesNotThrow(() -> executor.validateParams(flameRushDefinition(Map.of(
                "range", 6.0D,
                "targetAngle", 60.0D,
                "maxTargets", 5,
                "damageRatios", List.of(0.65D, 0.75D),
                "secondHitDelayTicks", 4,
                "burningUnlockLevel", 8,
                "burningChance", 35.0D,
                "burningDurationTicks", 100L
        ))));

        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(flameRushDefinition(Map.of(
                        "range", 6.0D,
                        "targetAngle", 60.0D,
                        "maxTargets", 5,
                        "damageRatios", List.of(0.65D, 0.75D),
                        "secondHitDelayTicks", 4,
                        "burningUnlockLevel", 11,
                        "burningChance", 35.0D,
                        "burningDurationTicks", 100L
                )))
        );
        assertEquals("burningUnlockLevel", exception.key());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 12. チャレンジングロアの実装契約
     * 検証契約: 挑発の範囲・上限・期間・各間隔は正数で、挑発間隔は演出間隔の倍数を必須とする。
     */
    @Test
    void challengingRoarValidatesAuraParams() {
        SwordsmanChallengingRoarExecutor executor = new SwordsmanChallengingRoarExecutor(activeSkillServices());
        Map<String, Object> valid = Map.of(
                "radius", 8.0D,
                "height", 8.0D,
                "maxTargets", 24,
                "durationTicks", 80,
                "visualIntervalTicks", 5,
                "tauntIntervalTicks", 20,
                "tauntHoldTicks", 21
        );

        assertDoesNotThrow(() -> executor.validateParams(challengingRoarDefinition(valid)));

        Map<String, Object> invalid = new java.util.LinkedHashMap<>(valid);
        invalid.put("tauntIntervalTicks", 22);
        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(challengingRoarDefinition(invalid))
        );
        assertEquals("tauntIntervalTicks", exception.key());
    }

    private static SkillDefinition astralEdgeDefinition(Map<String, Object> params) {
        return new SkillDefinition(
                "adventurer_astral_edge",
                "adventurer_astral_edge",
                "アストラルエッジ",
                null,
                "IRON_SWORD",
                List.of(),
                50L,
                0.0D,
                0L,
                1,
                null,
                params,
                List.of("active", "melee", "adventurer"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.ENERGY,
                8.0D
        );
    }

    private static SkillDefinition blastArrowDefinition(Map<String, Object> params) {
        return new SkillDefinition(
                "adventurer_blast_arrow",
                "adventurer_blast_arrow",
                "ブラストアロー",
                null,
                "SPECTRAL_ARROW",
                List.of(),
                100L,
                6.0D,
                0L,
                1,
                null,
                params,
                List.of("active", "ranged", "adventurer"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.ENERGY,
                12.0D
        );
    }

    private static SkillDefinition lightningBoltDefinition(Map<String, Object> params) {
        return new SkillDefinition(
                "adventurer_lightning_bolt",
                "adventurer_lightning_bolt",
                "ライトニングボルト",
                null,
                "LIGHTNING_ROD",
                List.of(),
                60L,
                0.0D,
                4L,
                1,
                null,
                params,
                List.of("active", "magic", "adventurer", "lightning"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                10.0D
        );
    }

    private static SkillDefinition manaBurstDefinition(Map<String, Object> params) {
        return new SkillDefinition(
                "adventurer_mana_burst",
                "adventurer_mana_burst",
                "マナバースト",
                null,
                "AMETHYST_SHARD",
                List.of(),
                60L,
                13.0D,
                2L,
                1,
                null,
                params,
                List.of("active", "magic", "adventurer"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                13.0D
        );
    }

    private static SkillDefinition shieldDrainDefinition(Map<String, Object> params) {
        return new SkillDefinition(
                "swordsman_shield_drain",
                "swordsman_shield_drain",
                "シールドドレイン",
                null,
                "HEART_OF_THE_SEA",
                List.of(),
                60L,
                0.0D,
                0L,
                1,
                null,
                params,
                List.of("active", "melee"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.ENERGY,
                10.0D
        );
    }

    private static SkillDefinition flameRushDefinition(Map<String, Object> params) {
        return new SkillDefinition(
                "swordsman_flame_rush",
                "swordsman_flame_rush",
                "フレイムラッシュ",
                null,
                "BLAZE_POWDER",
                List.of(),
                80L,
                0.0D,
                0L,
                1,
                null,
                params,
                List.of("active", "melee", "fire"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.ENERGY,
                14.0D,
                null,
                10
        );
    }

    private static SkillDefinition challengingRoarDefinition(Map<String, Object> params) {
        return new SkillDefinition(
                "swordsman_challenging_roar",
                "swordsman_challenging_roar",
                "チャレンジングロア",
                null,
                "GOAT_HORN",
                List.of(),
                400L,
                0.0D,
                0L,
                1,
                null,
                params,
                List.of("active"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.ENERGY,
                25.0D
        );
    }

    private static ActiveSkillServices activeSkillServices() {
        return new ActiveSkillServices(
            mock(SkillTargetingService.class),
            mock(SkillCombatService.class),
            mock(SkillEffectService.class),
            mock(SkillProjectileService.class),
            mock(SkillMovementService.class),
            mock(TemporarySkillEffectService.class),
            mock(SkillTaskService.class)
        );
    }

}
