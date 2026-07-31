package io.github.maaasu.astralRecord.feature.trainingdummy.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrainingDummyDefinitionTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/31-training-dummy/31_1-モデル定義.md
     * 章・見出し: # 31_1-モデル定義 > ## 1. カカシ定義
     * 検証契約: constructor入力とwithStats更新値にかかわらず最大HPをInteger.MAX_VALUEへ固定する。
     */
    @Test
    void maxHealthIsAlwaysFixedToIntegerMaximum() {
        TrainingDummyDefinition definition = new TrainingDummyDefinition(
                "dummy", "world", 0.0D, 64.0D, 0.0D, 0.0F,
                100.0D, 0.0D, 0.0D, false, 10.0D, 40L
        );

        assertEquals((double) Integer.MAX_VALUE, definition.maxHealth());
        assertEquals(
                (double) Integer.MAX_VALUE,
                definition.withStats(1.0D, 0.0D, 0.0D, false, 10.0D).maxHealth()
        );
    }
}
