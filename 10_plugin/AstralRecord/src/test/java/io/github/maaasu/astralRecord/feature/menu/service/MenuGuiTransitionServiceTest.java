package io.github.maaasu.astralRecord.feature.menu.service;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MenuGuiTransitionServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 2. GUI 遷移
     * 検証契約: プレイヤーインベントリのダミー表示はBE版だけヒカリゴケ、Java版は灰色の色付きガラス板を使用する。
     */
    @Test
    void selectsGlowLichenOnlyForBedrockPlayerInventoryDummy() {
        assertEquals(Material.GLOW_LICHEN, MenuGuiTransitionService.playerInventoryDummyMaterial(true));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, MenuGuiTransitionService.playerInventoryDummyMaterial(false));
    }
}
