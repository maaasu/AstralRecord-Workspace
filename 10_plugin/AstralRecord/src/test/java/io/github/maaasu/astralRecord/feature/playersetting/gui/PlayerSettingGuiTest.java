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
     * 検証契約: 設定項目を3行目の20〜24と4行目の29〜32へ配置し、左右枠と未使用slotは設定keyとして解決しない。
     */
    @Test
    void settingLayoutUsesInteriorPanelSlots() {
        PlayerSettingGui gui = new PlayerSettingGui(mock(PlayerSettingService.class));

        assertEquals(20, PlayerSettingGui.DAMAGE_LOG_SLOT);
        assertEquals(21, PlayerSettingGui.DAMAGE_LOG_MESSAGE_SLOT);
        assertEquals(22, PlayerSettingGui.PARTICLE_DENSITY_SLOT);
        assertEquals(23, PlayerSettingGui.PERFORMANCE_INFO_SLOT);
        assertEquals(24, PlayerSettingGui.DROP_LOG_SLOT);
        assertEquals(29, PlayerSettingGui.AUTO_SAVE_MESSAGE_SLOT);
        assertEquals(30, PlayerSettingGui.BUFF_SIDEBAR_DISPLAY_SLOT);
        assertEquals(31, PlayerSettingGui.ARMOR_DISPLAY_SLOT);
        assertEquals(32, PlayerSettingGui.ACTION_RING_HOLD_SELECT_SLOT);
        assertEquals(
            PlayerSettingKey.AUTO_SAVE_MESSAGE,
            gui.getKeyAtSlot(PlayerSettingGui.AUTO_SAVE_MESSAGE_SLOT)
        );
        assertEquals(
            PlayerSettingKey.BUFF_SIDEBAR_DISPLAY,
            gui.getKeyAtSlot(PlayerSettingGui.BUFF_SIDEBAR_DISPLAY_SLOT)
        );
        assertEquals(
            PlayerSettingKey.ARMOR_DISPLAY,
            gui.getKeyAtSlot(PlayerSettingGui.ARMOR_DISPLAY_SLOT)
        );
        assertEquals(
            PlayerSettingKey.ACTION_RING_HOLD_SELECT,
            gui.getKeyAtSlot(PlayerSettingGui.ACTION_RING_HOLD_SELECT_SLOT)
        );
        assertNull(gui.getKeyAtSlot(26));
        assertNull(gui.getKeyAtSlot(27));
        assertNull(gui.getKeyAtSlot(28));
        assertNull(gui.getKeyAtSlot(33));
    }
}
