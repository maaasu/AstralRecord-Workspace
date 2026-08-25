package io.github.maaasu.astralRecord.feature.mob.skill.skeletonarcher;

import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.service.MobProjectileService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class SkeletonArcherBowShotMobSkillExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-Mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 2. 追加手順
     * 検証契約: Mob executor は自身が宣言した少数の数値パラメーターだけを受け付ける。
     */
    @Test
    void acceptsOnlyDeclaredArrowParameters() {
        SkeletonArcherBowShotMobSkillExecutor executor = new SkeletonArcherBowShotMobSkillExecutor(
                mock(DamageService.class), mock(MobProjectileService.class)
        );

        assertDoesNotThrow(() -> executor.validate(new MobSkillBinding(
                SkeletonArcherBowShotMobSkillExecutor.SKILL_ID,
                null, null, null,
                Map.of("damageRatio", 0.85D, "projectileSpeed", 1.25D, "projectileHitRadius", 0.20D)
        )));
        assertThrows(IllegalArgumentException.class, () -> executor.validate(new MobSkillBinding(
                SkeletonArcherBowShotMobSkillExecutor.SKILL_ID,
                null, null, null,
                Map.of("unexpected", 1.0D)
        )));
    }
}
