package io.github.maaasu.astralRecord.feature.item.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PotionUseMovementAllowanceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/07_1-モデル定義.md
     * 章・見出し: # 07_1-モデル定義 > ## 4. ステータス種別 > ### 4.7 回復・ユーティリティ系
     * 検証契約: 緑色のコンポスター粒子の円は消耗品使用時移動許容距離が1ブロック以上の場合だけ表示する。
     */
    @Test
    void movementAllowanceRingRequiresAtLeastOneBlock() {
        assertFalse(PotionUseService.shouldDisplayMovementAllowanceRing(0.999D));
        assertFalse(PotionUseService.shouldDisplayMovementAllowanceRing(Double.NaN));
        assertTrue(PotionUseService.shouldDisplayMovementAllowanceRing(1.0D));
    }
}
