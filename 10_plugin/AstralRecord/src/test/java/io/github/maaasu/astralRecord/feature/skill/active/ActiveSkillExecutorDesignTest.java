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
        "adventurer_smash"
    );

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック
     * 検証契約: catalogが設計記載2 skill IDを各1回だけ返し全てPlayerActiveSkillExecutorである。
     */
    @Test
    void catalogContainsEveryDesignedSkillIdExactlyOnce() {
        List<SkillExecutor> executors = ActiveSkillExecutorCatalog.create(activeSkillServices());
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
