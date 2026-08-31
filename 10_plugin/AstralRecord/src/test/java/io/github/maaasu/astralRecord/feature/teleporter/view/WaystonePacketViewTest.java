package io.github.maaasu.astralRecord.feature.teleporter.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaystonePacketViewTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/25-teleporter/3-メソッド仕様/25_3-GUI・View.md
     * 章・見出し: # 25_3-GUI・View > ## ウェイストーン表示同期
     * 検証契約: BE版のWaystone packet表示は文字Display 1件だけ、Java版は従来のBlock 4件・文字1件・Item 1件を生成する。
     */
    @Test
    void keepsOnlyTextDisplayForBedrockWaystonePacketView() {
        assertEquals(1, WaystonePacketView.packetDisplayEntityCount(true));
        assertEquals(6, WaystonePacketView.packetDisplayEntityCount(false));
    }
}
