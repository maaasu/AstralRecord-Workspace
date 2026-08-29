package io.github.maaasu.astralRecord.feature.mob.skill.forestspider;

import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.service.MobProjectileService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ForestSpiderWebShotMobSkillExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 2. 追加手順
     * 検証契約: クモ糸スキルの executor は宣言済みの数値パラメーターだけを受け付け、未知のキーを拒否する。
     */
    @Test
    void acceptsOnlyDeclaredWebParameters() {
        ForestSpiderWebShotMobSkillExecutor executor = executor();

        assertDoesNotThrow(() -> executor.validate(new MobSkillBinding(
                ForestSpiderWebShotMobSkillExecutor.SKILL_ID,
                null, null, null,
                Map.of(
                        "damageRatio", 0.75D,
                        "projectileSpeed", 0.90D,
                        "projectileHitRadius", 0.25D,
                        "weaknessChance", 25.0D,
                        "weaknessDurationTicks", 100.0D
                )
        )));
        assertThrows(IllegalArgumentException.class, () -> executor.validate(new MobSkillBinding(
                ForestSpiderWebShotMobSkillExecutor.SKILL_ID,
                null, null, null,
                Map.of("unexpected", 1.0D)
        )));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 5. フォレストスパイダーのクモ糸
     * 検証契約: クモ糸スキルは既定で近距離の9m、40 tick cooldown、10 tick詠唱を使い、高低差のある対象を照準できる。
     */
    @Test
    void usesCloseThreeDimensionalTargetingDefaults() {
        ForestSpiderWebShotMobSkillExecutor executor = executor();
        MobSkillTiming timing = executor.defaultTiming();

        assertEquals(9.0D, timing.activationRange());
        assertEquals(40L, timing.cooldownTicks());
        assertEquals(10L, timing.castTimeTicks());
        assertTrue(executor.allowsVerticalTargeting());
    }

    private static ForestSpiderWebShotMobSkillExecutor executor() {
        return new ForestSpiderWebShotMobSkillExecutor(
                mock(DamageService.class),
                mock(ConditionService.class),
                mock(MobProjectileService.class)
        );
    }
}
