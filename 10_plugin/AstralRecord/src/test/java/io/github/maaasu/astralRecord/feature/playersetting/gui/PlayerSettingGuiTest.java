package io.github.maaasu.astralRecord.feature.playersetting.gui;

import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class PlayerSettingGuiTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-GUI・View.md
     * 章・見出し: # 11_3-GUI・View > ## 1. 設定 GUI 表示
     * 検証契約: 削除した拠点音楽設定の slot は key として解決せず、既存設定の slot 配置は維持する。
     */
    @Test
    void removedBaseMusicSlotIsNotResolved() {
        PlayerSettingGui gui = new PlayerSettingGui(mock(PlayerSettingService.class));

        assertNull(gui.getKeyAtSlot(29));
        assertEquals(
            PlayerSettingKey.ACTION_RING_HOLD_SELECT,
            gui.getKeyAtSlot(PlayerSettingGui.ACTION_RING_HOLD_SELECT_SLOT)
        );
    }
}
