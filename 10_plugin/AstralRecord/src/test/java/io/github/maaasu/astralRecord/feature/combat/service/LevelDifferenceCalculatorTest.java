package io.github.maaasu.astralRecord.feature.combat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LevelDifferenceCalculatorTest {

    /**
     * player/Mob絶対level差1ごとにEXP倍率を5%減らし最低10%にする。
     */
    @Test
    void experienceMultiplierUsesAbsoluteLevelDifference() {
        assertEquals(1.0D, LevelDifferenceCalculator.experienceMultiplier(10, 10), 0.0001D);
        assertEquals(0.50D, LevelDifferenceCalculator.experienceMultiplier(20, 10), 0.0001D);
        assertEquals(0.50D, LevelDifferenceCalculator.experienceMultiplier(10, 20), 0.0001D);
        assertEquals(0.10D, LevelDifferenceCalculator.experienceMultiplier(1, 100), 0.0001D);
    }

    /**
     * 正の基礎EXPはlevel差補正後も最低1を返す。
     */
    @Test
    void scaleExperienceKeepsPositiveExperienceAtLeastOne() {
        assertEquals(1, LevelDifferenceCalculator.scaleExperience(1, 1, 100));
    }
}
