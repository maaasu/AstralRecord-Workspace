package io.github.maaasu.astralRecord.feature.menu.service;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MenuGuiTransitionServiceTest {

    /**
     * プレイヤーインベントリのダミー表示はBE版だけヒカリゴケ、Java版は灰色の色付きガラス板を使用する。
     */
    @Test
    void selectsGlowLichenOnlyForBedrockPlayerInventoryDummy() {
        assertEquals(Material.GLOW_LICHEN, MenuGuiTransitionService.playerInventoryDummyMaterial(true));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, MenuGuiTransitionService.playerInventoryDummyMaterial(false));
    }
}
