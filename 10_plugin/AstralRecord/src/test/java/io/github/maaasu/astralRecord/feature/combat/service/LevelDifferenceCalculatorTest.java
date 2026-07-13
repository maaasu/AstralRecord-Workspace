package io.github.maaasu.astralRecord.feature.combat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LevelDifferenceCalculatorTest {

    @Test
    void experienceMultiplierUsesAbsoluteLevelDifference() {
        assertEquals(1.0D, LevelDifferenceCalculator.experienceMultiplier(10, 10), 0.0001D);
        assertEquals(0.50D, LevelDifferenceCalculator.experienceMultiplier(20, 10), 0.0001D);
        assertEquals(0.50D, LevelDifferenceCalculator.experienceMultiplier(10, 20), 0.0001D);
        assertEquals(0.10D, LevelDifferenceCalculator.experienceMultiplier(1, 100), 0.0001D);
    }

    @Test
    void scaleExperienceKeepsPositiveExperienceAtLeastOne() {
        assertEquals(100, LevelDifferenceCalculator.scaleExperience(100, 10, 10));
        assertEquals(50, LevelDifferenceCalculator.scaleExperience(100, 20, 10));
        assertEquals(1, LevelDifferenceCalculator.scaleExperience(1, 1, 100));
        assertEquals(0, LevelDifferenceCalculator.scaleExperience(0, 1, 100));
    }
}
