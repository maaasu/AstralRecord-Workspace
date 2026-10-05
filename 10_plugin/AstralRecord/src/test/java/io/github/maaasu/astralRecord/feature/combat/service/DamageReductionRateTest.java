package io.github.maaasu.astralRecord.feature.combat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DamageReductionRateTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 1. damage 計算 > ### 共通被ダメージ軽減率
     * 検証契約: 有限の軽減率を0〜100%へ補正し、非有限値は軽減なしとする。
     */
    @Test
    void normalizesReductionRateToFiniteDamageMultiplier() {
        assertEquals(1.0D, DamageService.damageReductionMultiplier(-5.0D));
        assertEquals(1.0D, DamageService.damageReductionMultiplier(0.0D));
        assertEquals(0.625D, DamageService.damageReductionMultiplier(37.5D));
        assertEquals(0.0D, DamageService.damageReductionMultiplier(100.0D));
        assertEquals(0.0D, DamageService.damageReductionMultiplier(200.0D));
        assertEquals(1.0D, DamageService.damageReductionMultiplier(Double.NaN));
        assertEquals(1.0D, DamageService.damageReductionMultiplier(Double.POSITIVE_INFINITY));
        assertEquals(1.0D, DamageService.damageReductionMultiplier(Double.NEGATIVE_INFINITY));
    }
}
