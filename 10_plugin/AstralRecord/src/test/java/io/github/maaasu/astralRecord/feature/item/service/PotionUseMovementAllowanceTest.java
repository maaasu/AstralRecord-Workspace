package io.github.maaasu.astralRecord.feature.item.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PotionUseMovementAllowanceTest {

    /**
     * 緑色のコンポスター粒子の円は消耗品使用時移動許容距離が1ブロック以上の場合だけ表示する。
     */
    @Test
    void movementAllowanceRingRequiresAtLeastOneBlock() {
        assertFalse(PotionUseService.shouldDisplayMovementAllowanceRing(0.999D));
        assertFalse(PotionUseService.shouldDisplayMovementAllowanceRing(Double.NaN));
        assertTrue(PotionUseService.shouldDisplayMovementAllowanceRing(1.0D));
    }
}
