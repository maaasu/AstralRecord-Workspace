package io.github.maaasu.astralRecord.feature.combat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LevelDifferenceCalculatorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 4. MobDropService メソッド仕様 > ### ドロップ確定
     * 検証契約: player/Mob絶対level差1ごとにEXP倍率を5%減らし最低10%にする。
     */
    @Test
    void experienceMultiplierUsesAbsoluteLevelDifference() {
        assertEquals(1.0D, LevelDifferenceCalculator.experienceMultiplier(10, 10), 0.0001D);
        assertEquals(0.50D, LevelDifferenceCalculator.experienceMultiplier(20, 10), 0.0001D);
        assertEquals(0.50D, LevelDifferenceCalculator.experienceMultiplier(10, 20), 0.0001D);
        assertEquals(0.10D, LevelDifferenceCalculator.experienceMultiplier(1, 100), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 11. level difference
     * 検証契約: 正の基礎EXPはlevel差補正後も最低1を返す。
     */
    @Test
    void scaleExperienceKeepsPositiveExperienceAtLeastOne() {
        assertEquals(1, LevelDifferenceCalculator.scaleExperience(1, 1, 100));
    }
}
