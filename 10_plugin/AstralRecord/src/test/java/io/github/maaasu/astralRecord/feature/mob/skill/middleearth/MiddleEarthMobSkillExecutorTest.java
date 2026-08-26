package io.github.maaasu.astralRecord.feature.mob.skill.middleearth;

import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.service.MobProjectileService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class MiddleEarthMobSkillExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 4. ミドルアースの遺跡の専用契約
     * 検証契約: ミドルアースの専用Mobスキルは、マスタで公開した数値パラメーターだけを受け付ける。
     */
    @Test
    void acceptsOnlyDeclaredMiddleEarthParameters() {
        MiddleEarthPiglinRushMobSkillExecutor piglin = new MiddleEarthPiglinRushMobSkillExecutor(
                mock(MobService.class), mock(DamageService.class)
        );
        AllThingsElIceSphereMobSkillExecutor iceSphere = new AllThingsElIceSphereMobSkillExecutor(
                mock(DamageService.class), mock(ConditionService.class), mock(MobProjectileService.class)
        );
        AllThingsElChargeMobSkillExecutor charge = new AllThingsElChargeMobSkillExecutor(
                mock(MobService.class), mock(DamageService.class)
        );

        assertDoesNotThrow(() -> piglin.validate(binding(piglin.id(), Map.of("speed", 1.10D, "damageRatio", 0.90D))));
        assertDoesNotThrow(() -> iceSphere.validate(binding(iceSphere.id(), Map.of(
                "speed", 0.75D, "damageRatio", 0.70D, "hitRadius", 0.20D,
                "frozenDurationTicks", 20.0D, "spreadDegrees", 24.0D
        ))));
        assertDoesNotThrow(() -> charge.validate(binding(charge.id(), Map.of("speed", 1.40D, "damageRatio", 1.0D, "holdTicks", 20.0D))));
        assertThrows(IllegalArgumentException.class, () -> charge.validate(binding(charge.id(), Map.of("unexpected", 1.0D))));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 4. ミドルアースの遺跡の専用契約
     * 検証契約: 万物のエルの突進回数は残HP割合の一の位を切り捨てて求める。
     */
    @Test
    void resolvesChargeCountFromHealthDecile() {
        assertEquals(2, AllThingsElChargeMobSkillExecutor.resolveChargeCount(80.0D, 100.0D));
        assertEquals(2, AllThingsElChargeMobSkillExecutor.resolveChargeCount(89.9D, 100.0D));
        assertEquals(9, AllThingsElChargeMobSkillExecutor.resolveChargeCount(15.0D, 100.0D));
    }

    private MobSkillBinding binding(String id, Map<String, Double> params) {
        return new MobSkillBinding(id, null, null, null, params);
    }
}
