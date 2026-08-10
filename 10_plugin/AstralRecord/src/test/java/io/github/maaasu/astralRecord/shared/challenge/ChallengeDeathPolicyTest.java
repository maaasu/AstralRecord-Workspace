package io.github.maaasu.astralRecord.shared.challenge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengeDeathPolicyTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_1-モデル定義.md
     * 章・見出し: # 26_1-モデル定義 > ## 1. ボス挑戦設定
     * 検証契約: deathLimit=0は0回まで許容し、1回目の死亡で終了条件を満たす。
     */
    @Test
    void zeroLimitEndsOnFirstDeath() {
        assertFalse(ChallengeDeathPolicy.isExceeded(0, 0));
        assertTrue(ChallengeDeathPolicy.isExceeded(1, 0));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_1-モデル定義.md
     * 章・見出し: # 32_1-モデル定義 > ## 1. DungeonDefinition
     * 検証契約: 設定回数までは死亡可能であり、設定回数を超えた次の死亡だけが終了条件となる。
     */
    @Test
    void configuredNumberOfDeathsIsAllowed() {
        assertFalse(ChallengeDeathPolicy.isExceeded(2, 2));
        assertTrue(ChallengeDeathPolicy.isExceeded(3, 2));
    }
}
