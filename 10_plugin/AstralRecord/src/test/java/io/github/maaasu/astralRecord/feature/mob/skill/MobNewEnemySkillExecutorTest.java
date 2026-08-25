package io.github.maaasu.astralRecord.feature.mob.skill;

import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.skill.clayguard.ClayGuardLeapMobSkillExecutor;
import io.github.maaasu.astralRecord.feature.mob.skill.mossshell.MossShellShellBashMobSkillExecutor;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class MobNewEnemySkillExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 2. 追加手順
     * 検証契約: クレイガードの着地スキルは半径とダメージ倍率だけを有限な範囲で受け付ける。
     */
    @Test
    void clayGuardLeapAcceptsOnlyBoundedParameters() {
        ClayGuardLeapMobSkillExecutor executor = new ClayGuardLeapMobSkillExecutor(
                mock(MobService.class), mock(DamageService.class), mock(ParticleDisplayService.class)
        );

        assertDoesNotThrow(() -> executor.validate(new MobSkillBinding(
                ClayGuardLeapMobSkillExecutor.SKILL_ID,
                null, null, null,
                Map.of("radius", 2.0D, "damageRatio", 0.85D)
        )));
        assertThrows(IllegalArgumentException.class, () -> executor.validate(new MobSkillBinding(
                ClayGuardLeapMobSkillExecutor.SKILL_ID,
                null, null, null,
                Map.of("radius", 3.1D)
        )));
        assertThrows(IllegalArgumentException.class, () -> executor.validate(new MobSkillBinding(
                ClayGuardLeapMobSkillExecutor.SKILL_ID,
                null, null, null,
                Map.of("unexpected", 1.0D)
        )));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 2. 追加手順
     * 検証契約: モスシェルの甲羅打ちは個別パラメーターを持たない専用 Mob スキルである。
     */
    @Test
    void mossShellBashRejectsParameters() {
        MossShellShellBashMobSkillExecutor executor = new MossShellShellBashMobSkillExecutor(mock(DamageService.class));

        assertDoesNotThrow(() -> executor.validate(new MobSkillBinding(
                MossShellShellBashMobSkillExecutor.SKILL_ID,
                null, null, null,
                Map.of()
        )));
        assertThrows(IllegalArgumentException.class, () -> executor.validate(new MobSkillBinding(
                MossShellShellBashMobSkillExecutor.SKILL_ID,
                null, null, null,
                Map.of("damageRatio", 1.0D)
        )));
    }
}
