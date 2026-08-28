package io.github.maaasu.astralRecord.feature.mob.model;

import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobShieldRechargeTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_1-モデル定義.md
     * 章・見出し: # 12_1-モデル定義 > ## 18. Mob インスタンス
     * 検証契約: recovery amountがspawn値未満でも完了後はその値を満タン表示capacityとして扱う。
     */
    @Test
    void rechargeAmountBelowSpawnShieldBecomesFullCycleCapacity() {
        MobInstance mob = DesignTestFixtures.mobInstance(
            100.0D, 0.0D, 0.0D, new MobShieldConfig(true, 100.0D, 10.0D, 50.0D)
        );
        mob.currentShield(0.0D, 1_000L);

        assertTrue(mob.startShieldRecharge(1_000L, 10_000L));
        assertFalse(mob.completeShieldRechargeIfReady(10_999L));
        assertTrue(mob.completeShieldRechargeIfReady(11_000L));
        assertEquals(50.0D, mob.currentShield(), 0.0001D);
        assertEquals(50.0D, mob.shieldDisplayCapacity(), 0.0001D);
        assertNull(mob.shieldRechargeState());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_1-モデル定義.md
     * 章・見出し: # 12_1-モデル定義 > ## 18. Mob インスタンス
     * 検証契約: recovery amountはspawn時shield値を超過でき、実際の吸収capacityにもなる。
     */
    @Test
    void rechargeAmountAboveSpawnShieldIsNotCappedByTemplateMax() {
        MobInstance mob = DesignTestFixtures.mobInstance(
            100.0D, 0.0D, 0.0D, new MobShieldConfig(true, 100.0D, 0.0D, 500.0D)
        );
        mob.currentShield(0.0D, 1_000L);

        assertTrue(mob.startShieldRecharge(1_000L, 0L));
        assertTrue(mob.completeShieldRechargeIfReady(1_000L));
        assertEquals(500.0D, mob.currentShield(), 0.0001D);
        assertEquals(500.0D, mob.shieldDisplayCapacity(), 0.0001D);

        mob.currentShield(450.0D, 1_001L);
        assertEquals(450.0D, mob.currentShield(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_1-モデル定義.md
     * 章・見出し: # 12_1-モデル定義 > ## 18. Mob インスタンス
     * 検証契約: Mob HP回復は最大HPを超えず、実際の増加量を返す。
     */
    @Test
    void healthRecoveryIsCappedAtMaxHealthAndReturnsActualAmount() {
        MobInstance mob = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D);
        mob.currentHealth(35.0D);

        assertEquals(65.0D, mob.recoverHealth(80.0D), 0.0001D);
        assertEquals(100.0D, mob.currentHealth(), 0.0001D);
    }
}
