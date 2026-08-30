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
import io.github.maaasu.astralRecord.feature.skill.executor.active.hunter.HunterFadeShotExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.hunter.HunterArrowRainExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.hunter.HunterCrashArrowExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.mage.MageFireballExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.mage.MageFrostBlizzardExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.mage.MageHealAuraExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.mage.MageSparkingExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman.SwordsmanBastionStrikeExecutor;
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
        "hunter_fade_shot",
        "hunter_crash_arrow",
        "hunter_heal_arrow",
        "mage_fireball",
        "mage_heal_aura",
        "mage_sparking",
        "mage_frost_blizzard",
        "swordsman_shield_drain",
        "swordsman_flame_rush",
        "swordsman_challenging_roar",
        "swordsman_bastion_strike",
        "hunter_arrow_rain"
    );

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック
     * 検証契約: catalogが設計記載18 skill IDを各1回だけ返し全てPlayerActiveSkillExecutorである。
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
                "damageRatios", List.of(1.2D, 0.6D)
        ))));

        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(astralEdgeDefinition(Map.of(
                        "reach", 5.5D,
                        "maxTargets", 5,
                        "damageRatios", List.of(1.2D)
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
                "damageRatio", 1.44D,
                "maxTargets", 6,
                "projectileSpeed", 1.35D,
                "projectileHitRadius", 0.45D
        ))));

        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(blastArrowDefinition(Map.of(
                        "range", 14.0D,
                        "radius", 2.25D,
                        "damageRatio", 1.44D,
                        "maxTargets", 0,
                        "projectileSpeed", 1.35D,
                        "projectileHitRadius", 0.45D
                )))
        );
        assertEquals("maxTargets", exception.key());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 18. ハンタークラッシュアローの実装契約
     * 検証契約: クラッシュアローは射程・低いHP倍率・シールドブレイク倍率・飛翔体値を正数として要求する。
     */
    @Test
    void crashArrowValidatesDataDrivenParams() {
        HunterCrashArrowExecutor executor = new HunterCrashArrowExecutor(activeSkillServices());

        assertDoesNotThrow(() -> executor.validateParams(crashArrowDefinition(Map.of(
                "range", 14.0D,
                "damageRatio", 0.45D,
                "shieldBreakMultiplier", 3.0D,
                "projectileSpeed", 1.35D,
                "projectileHitRadius", 0.45D
        ))));

        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(crashArrowDefinition(Map.of(
                        "range", 14.0D,
                        "damageRatio", 0.45D,
                        "shieldBreakMultiplier", 0.0D,
                        "projectileSpeed", 1.35D,
                        "projectileHitRadius", 0.45D
                )))
        );
        assertEquals("shieldBreakMultiplier", exception.key());
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
                "damageRatio", 1.74D,
                "chainRadius", 5.0D,
                "chainDamageRatio", 0.48D,
                "maxChainTargets", 2,
                "projectileSpeed", 2.8D,
                "projectileHitRadius", 0.45D
        ))));

        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(lightningBoltDefinition(Map.of(
                        "range", 14.0D,
                        "damageRatio", 1.74D,
                        "chainRadius", 5.0D,
                        "chainDamageRatio", 0.48D,
                        "maxChainTargets", 0,
                        "projectileSpeed", 2.8D,
                        "projectileHitRadius", 0.45D
                )))
        );
        assertEquals("maxChainTargets", exception.key());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 23. メイジ スパーキングの実装契約
     * 検証契約: スパーキングは正の弾数・螺旋半径成長・回転角・命中半径・寿命・感電時間と、0〜100%の感電率を要求する。
     */
    @Test
    void sparkingValidatesBouncingProjectileParams() {
        MageSparkingExecutor executor = new MageSparkingExecutor(activeSkillServices());
        Map<String, Object> valid = Map.of(
                "damageRatio", 1.2D,
                "projectileCount", 5,
                "spiralRadiusGrowth", 0.10D,
                "spiralDegreesPerTick", 14.4D,
                "projectileHitRadius", 0.60D,
                "durationTicks", 50,
                "shockChance", 25.0D,
                "shockDurationTicks", 100
        );

        assertDoesNotThrow(() -> executor.validateParams(sparkingDefinition(valid)));

        Map<String, Object> invalid = new java.util.LinkedHashMap<>(valid);
        invalid.put("shockChance", 101.0D);
        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(sparkingDefinition(invalid))
        );
        assertEquals("shockChance", exception.key());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 24. メイジ フロストブリザードの実装契約 > ### 24.1 数値・移動・対象
     * 検証契約: フロストブリザードは正の範囲・速度・持続・攻撃間隔・対象数と、0以上の中心・上方向velocityを要求する。
     */
    @Test
    void frostBlizzardValidatesPersistentVortexParams() {
        MageFrostBlizzardExecutor executor = new MageFrostBlizzardExecutor(activeSkillServices());
        Map<String, Object> valid = new java.util.LinkedHashMap<>();
        valid.put("damageRatio", 0.24D);
        valid.put("radius", 2.75D);
        valid.put("height", 2.5D);
        valid.put("movementSpeed", 0.18D);
        valid.put("durationTicks", 200);
        valid.put("damageIntervalTicks", 10);
        valid.put("maxTargets", 8);
        valid.put("orbitVelocity", 0.16D);
        valid.put("inwardVelocity", 0.08D);
        valid.put("verticalVelocity", 0.04D);

        assertDoesNotThrow(() -> executor.validateParams(frostBlizzardDefinition(valid)));

        valid.put("damageIntervalTicks", 0);
        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(frostBlizzardDefinition(valid))
        );
        assertEquals("damageIntervalTicks", exception.key());
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
                "damageRatio", 1.32D,
                "maxTargets", 6
        ))));

        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(manaBurstDefinition(Map.of(
                        "range", 7.0D,
                        "angle", 360.0D,
                        "damageRatio", 1.32D,
                        "maxTargets", 6
                )))
        );
        assertEquals("angle", exception.key());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 16. ハンターフェイドショットの実装契約 > ### 16.1 散弾・移動・演出
     * 検証契約: フェイドショットは正の射程・倍率・飛翔体値・後退velocity、3〜9の奇数弾数、60度以下の正の散弾全角を必須とする。
     */
    @Test
    void fadeShotValidatesScatterAndBackstepParams() {
        HunterFadeShotExecutor executor = new HunterFadeShotExecutor(activeSkillServices());

        assertDoesNotThrow(() -> executor.validateParams(fadeShotDefinition(Map.of(
                "range", 9.0D,
                "damageRatio", 0.384D,
                "pelletCount", 5,
                "spreadAngle", 30.0D,
                "projectileSpeed", 1.8D,
                "projectileHitRadius", 0.30D,
                "backstepVelocity", 0.35D
        ))));

        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(fadeShotDefinition(Map.of(
                        "range", 9.0D,
                        "damageRatio", 0.384D,
                        "pelletCount", 4,
                        "spreadAngle", 30.0D,
                        "projectileSpeed", 1.8D,
                        "projectileHitRadius", 0.30D,
                        "backstepVelocity", 0.35D
                )))
        );
        assertEquals("pelletCount", exception.key());
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
                "damageRatio", 0.975D,
                "shieldBreakMultiplier", 3.0D,
                "shieldAbsorbRatio", 0.50D
        ))));

        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(shieldDrainDefinition(Map.of(
                        "range", 6.0D,
                        "targetAngle", 40.0D,
                        "damageRatio", 0.975D,
                        "shieldBreakMultiplier", 3.0D,
                        "shieldAbsorbRatio", 1.5D
                )))
        );
        assertEquals("shieldAbsorbRatio", exception.key());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 11. フレイムラッシュの実装契約
     * 検証契約: フレイムラッシュは正しい前方範囲・2撃倍率・炎上条件を要求し、炎上率0%をLv.1〜7の有効値として許可し、負値を拒否する。
     */
    @Test
    void flameRushValidatesTwoHitAndBurningParams() {
        SwordsmanFlameRushExecutor executor = new SwordsmanFlameRushExecutor(activeSkillServices());

        assertDoesNotThrow(() -> executor.validateParams(flameRushDefinition(Map.of(
                "range", 6.0D,
                "targetAngle", 60.0D,
                "maxTargets", 5,
                "damageRatios", List.of(0.78D, 0.90D),
                "secondHitDelayTicks", 4,
                "burningChance", 0.0D,
                "burningDurationTicks", 100L
        ))));

        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(flameRushDefinition(Map.of(
                        "range", 6.0D,
                        "targetAngle", 60.0D,
                        "maxTargets", 5,
                        "damageRatios", List.of(0.78D, 0.90D),
                        "secondHitDelayTicks", 4,
                        "burningChance", -1.0D,
                        "burningDurationTicks", 100L
                )))
        );
        assertEquals("burningChance", exception.key());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 21. メイジファイアーボールの実装契約 > ### 21.1 数値・対象・終端
     * 検証契約: ファイアーボールは正の射程・爆発半径・火属性倍率・飛翔体値と、1体以上の範囲上限を要求する。
     */
    @Test
    void fireballValidatesProjectileAndImpactParams() {
        MageFireballExecutor executor = new MageFireballExecutor(activeSkillServices());

        assertDoesNotThrow(() -> executor.validateParams(fireballDefinition(Map.of(
                "range", 16.0D,
                "radius", 2.25D,
                "damageRatio", 1.32D,
                "maxTargets", 4,
                "projectileSpeed", 1.45D,
                "projectileHitRadius", 0.45D
        ))));

        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(fireballDefinition(Map.of(
                        "range", 16.0D,
                        "radius", 2.25D,
                        "damageRatio", 1.32D,
                        "maxTargets", 0,
                        "projectileSpeed", 1.45D,
                        "projectileHitRadius", 0.45D
                )))
        );
        assertEquals("maxTargets", exception.key());
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 13. バスティオンストライクの実装契約 > ### 13.1 数値・対象・演出
     * 検証契約: バスティオンストライクは正の射程・対象角・ダメージと現在MP全消費指定を必須とする。
     */
    @Test
    void bastionStrikeValidatesCombatParams() {
        SwordsmanBastionStrikeExecutor executor = new SwordsmanBastionStrikeExecutor(activeSkillServices());

        assertDoesNotThrow(() -> executor.validateParams(bastionStrikeDefinition(Map.of(
                "range", 6.0D,
                "targetAngle", 40.0D,
                "damageRatio", 1.875D,
                "consumeAllCurrentMana", true,
                "levelFiveRequiredManaRatio", 0.80D
        ))));

        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(bastionStrikeDefinition(Map.of(
                        "range", 6.0D,
                        "targetAngle", 40.0D,
                        "damageRatio", 1.875D,
                        "consumeAllCurrentMana", false,
                        "levelFiveRequiredManaRatio", 0.80D
                )))
        );
        assertEquals("consumeAllCurrentMana", exception.key());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 17. ハンター アローレインの実装契約
     * 検証契約: アローレインは射程・範囲・矢数・2撃倍率・初弾と雨矢の飛翔体仕様をmasterから要求する。
     */
    @Test
    void arrowRainValidatesBallisticVolleyParams() {
        HunterArrowRainExecutor executor = new HunterArrowRainExecutor(activeSkillServices());

        assertDoesNotThrow(() -> executor.validateParams(arrowRainDefinition(Map.of(
                "range", 18.0D,
                "radius", 3.0D,
                "arrowCount", 15,
                "damageRatios", List.of(0.84D, 0.36D),
                "openingSpeed", 1.60D,
                "openingHitRadius", 0.45D,
                "rainHitRadius", 0.75D
        ))));

        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(arrowRainDefinition(Map.of(
                        "range", 18.0D,
                        "radius", 3.0D,
                        "arrowCount", 15,
                        "damageRatios", List.of(0.84D),
                        "openingSpeed", 1.60D,
                        "openingHitRadius", 0.45D,
                        "rainHitRadius", 0.75D
                )))
        );
        assertEquals("damageRatios", exception.key());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 22. メイジ ヒールオーラの実装契約
     * 検証契約: ヒールオーラは正の範囲・高さ・回復量を必須とする。
     */
    @Test
    void healAuraValidatesImmediateHealParams() {
        MageHealAuraExecutor executor = new MageHealAuraExecutor(activeSkillServices());

        assertDoesNotThrow(() -> executor.validateParams(healAuraDefinition(Map.of(
                "radius", 4.0D,
                "height", 3.0D,
                "healAmount", 5.0D
        ))));

        SkillParameterException exception = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(healAuraDefinition(Map.of(
                        "radius", 4.0D,
                        "height", 0.0D,
                        "healAmount", 5.0D
                )))
        );
        assertEquals("height", exception.key());
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

    private static SkillDefinition crashArrowDefinition(Map<String, Object> params) {
        return new SkillDefinition(
                "hunter_crash_arrow",
                "hunter_crash_arrow",
                "クラッシュアロー",
                null,
                "SPECTRAL_ARROW",
                List.of(),
                120L,
                14.0D,
                20L,
                1,
                null,
                params,
                List.of("active", "ranged"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.ENERGY,
                14.0D
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

    private static SkillDefinition sparkingDefinition(Map<String, Object> params) {
        return new SkillDefinition(
                "mage_sparking",
                "mage_sparking",
                "スパーキング",
                null,
                "LIGHTNING_ROD",
                List.of(),
                160L,
                0.0D,
                4L,
                1,
                null,
                params,
                List.of("active", "magic", "lightning"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                14.0D
        );
    }

    private static SkillDefinition frostBlizzardDefinition(Map<String, Object> params) {
        return new SkillDefinition(
                "mage_frost_blizzard",
                "mage_frost_blizzard",
                "フロストブリザード",
                null,
                "DIAMOND_NAUTILUS_ARMOR",
                List.of(),
                400L,
                0.0D,
                20L,
                1,
                null,
                params,
                List.of("active", "magic"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                40.0D
        );
    }

    private static SkillDefinition shieldDrainDefinition(Map<String, Object> params) {
        return new SkillDefinition(
                "swordsman_shield_drain",
                "swordsman_shield_drain",
                "シールドドレイン",
                null,
                "TUBE_CORAL",
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

    private static SkillDefinition fadeShotDefinition(Map<String, Object> params) {
        return new SkillDefinition(
                "hunter_fade_shot",
                "hunter_fade_shot",
                "フェイドショット",
                null,
                "CROSSBOW",
                List.of(),
                80L,
                0.0D,
                0L,
                1,
                null,
                params,
                List.of("active", "ranged"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.ENERGY,
                14.0D
        );
    }

    private static SkillDefinition flameRushDefinition(Map<String, Object> params) {
        return new SkillDefinition(
                "swordsman_flame_rush",
                "swordsman_flame_rush",
                "フレイムラッシュ",
                null,
                "CRIMSON_ROOTS",
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

    private static SkillDefinition fireballDefinition(Map<String, Object> params) {
        return new SkillDefinition(
                "mage_fireball",
                "mage_fireball",
                "ファイアーボール",
                null,
                "FIRE_CHARGE",
                List.of(),
                80L,
                4.0D,
                4L,
                1,
                null,
                params,
                List.of("active", "magic", "mage", "fire"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                12.0D,
                null,
                5
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

    private static SkillDefinition bastionStrikeDefinition(Map<String, Object> params) {
        return new SkillDefinition(
                "swordsman_bastion_strike",
                "swordsman_bastion_strike",
                "バスティオンストライク",
                null,
                "SHIELD",
                List.of(),
                100L,
                0.0D,
                0L,
                1,
                null,
                params,
                List.of("active", "melee"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                0.0D
        );
    }

    private static SkillDefinition arrowRainDefinition(Map<String, Object> params) {
        return new SkillDefinition(
                "hunter_arrow_rain",
                "hunter_arrow_rain",
                "アローレイン",
                null,
                "SPECTRAL_ARROW",
                List.of(),
                240L,
                8.0D,
                40L,
                1,
                null,
                params,
                List.of("active", "ranged", "hunter"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.ENERGY,
                16.0D
        );
    }

    private static SkillDefinition healAuraDefinition(Map<String, Object> params) {
        return new SkillDefinition(
                "mage_heal_aura",
                "mage_heal_aura",
                "ヒールオーラ",
                null,
                "AMETHYST_CLUSTER",
                List.of(),
                40L,
                0.0D,
                0L,
                1,
                null,
                params,
                List.of("active", "magic", "support"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                6.0D,
                null,
                5
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
