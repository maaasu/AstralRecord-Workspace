package io.github.maaasu.astralRecord.feature.item.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class EquipmentProcessingDisplayStateTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_1-モデル定義.md
     * 章・見出し: # 09_1-モデル定義 > ## 1. 画面種別 > ### 装備加工の表示状態
     * 検証契約: 修理タブは常に修理表示とし、強化タブは次の実行が状態変化のときだけ状態変化表示へ自動で切り替える。
     */
    @Test
    void resolvesDisplayStateFromActiveTabAndNextOperation() {
        assertSame(EquipmentProcessingDisplayState.REPAIR,
            EquipmentProcessingDisplayState.from(EquipmentProcessingMode.REPAIR, true));
        assertSame(EquipmentProcessingDisplayState.ENHANCEMENT,
            EquipmentProcessingDisplayState.from(EquipmentProcessingMode.ENHANCEMENT, false));
        assertSame(EquipmentProcessingDisplayState.TRANSCENDENCE,
            EquipmentProcessingDisplayState.from(EquipmentProcessingMode.ENHANCEMENT, true));
        assertEquals("状態変化", EquipmentProcessingDisplayState.TRANSCENDENCE.displayName());
    }
}
