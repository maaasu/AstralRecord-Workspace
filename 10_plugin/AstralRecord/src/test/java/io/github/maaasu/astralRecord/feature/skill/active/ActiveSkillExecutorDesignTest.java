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
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ActiveSkillExecutorDesignTest {

    private static final Set<String> EXPECTED_SKILL_IDS = Set.of(
        "adventurer_astral_edge",
        "swordsman_blade_wave",
        "swordsman_crescent_slash",
        "swordsman_earthbreaker",
        "swordsman_fortress_guard",
        "swordsman_piercing_thrust",
        "swordsman_vanguard_rush",
        "swordsman_war_cry",
        "swordsman_whirlwind",
        "hunter_arrow_rain",
        "hunter_backstep_shot",
        "hunter_fan_shot",
        "hunter_piercing_arrow",
        "hunter_power_shot",
        "hunter_rapid_fire",
        "hunter_ricochet",
        "hunter_snare_trap",
        "mage_arcane_lance",
        "mage_blink",
        "mage_chain_lightning",
        "mage_elemental_storm",
        "mage_fireball",
        "mage_frost_nova",
        "mage_mana_barrier",
        "mage_meteor"
    );

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック
     * 検証契約: catalogが設計記載25 skill IDを各1回だけ返し全てPlayerActiveSkillExecutorである。
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
