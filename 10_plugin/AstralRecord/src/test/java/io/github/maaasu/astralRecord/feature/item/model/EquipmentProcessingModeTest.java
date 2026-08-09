package io.github.maaasu.astralRecord.feature.item.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

class EquipmentProcessingModeTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_1-モデル定義.md
     * 章・見出し: # 09_1-モデル定義 > ## 1. 画面種別
     * 検証契約: 装備加工画面の holder contentId を修理・強化モードへ安定して復元する。
     */
    @Test
    void restoresModeFromStableHolderContentId() {
        assertSame(EquipmentProcessingMode.REPAIR, EquipmentProcessingMode.fromContentId("repair"));
        assertSame(EquipmentProcessingMode.ENHANCEMENT, EquipmentProcessingMode.fromContentId("ENHANCEMENT"));
        assertNull(EquipmentProcessingMode.fromContentId("unknown"));
        assertNull(EquipmentProcessingMode.fromContentId(null));
    }
}
