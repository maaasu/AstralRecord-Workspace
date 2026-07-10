package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobDropPresentationServiceTest {

    @Test
    void enemyRareDropIncludesZeroPointOnePercentBoundary() {
        assertTrue(MobDropPresentationService.isRareDrop(MobCategory.ENEMY, 0.1D));
        assertFalse(MobDropPresentationService.isRareDrop(MobCategory.ENEMY, 0.1001D));
    }

    @Test
    void bossRareDropIncludesFivePercentBoundary() {
        assertTrue(MobDropPresentationService.isRareDrop(MobCategory.BOSS, 5.0D));
        assertFalse(MobDropPresentationService.isRareDrop(MobCategory.BOSS, 5.0001D));
    }

    @Test
    void dropRateFormattingRemovesOnlyUnnecessaryTrailingZeros() {
        assertEquals("5", MobDropPresentationService.formatDropRate(5.0D));
        assertEquals("0.1", MobDropPresentationService.formatDropRate(0.1D));
        assertEquals("0.0125", MobDropPresentationService.formatDropRate(0.0125D));
        assertEquals("0.00001", MobDropPresentationService.formatDropRate(0.00001D));
    }
}
