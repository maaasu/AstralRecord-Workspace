package io.github.maaasu.astralRecord.feature.menu.event;

import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockCraftingInteractionTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-イベント.md
     * 章・見出し: # 09_3-イベント > ## 3. クラフト枠・画面ライフサイクル
     * 検証契約: BE プレイヤーではクラフトショートカットの4枠とリザルト枠へのクリックを停止し、Java版では停止しない。
     */
    @Test
    void blocksBedrockCraftingShortcutAndResultClicksOnly() {
        assertTrue(MenuOpenEventHandler.isBedrockCraftingClick(
            true, InventoryType.CRAFTING, 0, false, false, InventoryAction.PICKUP_ALL
        ));
        assertTrue(MenuOpenEventHandler.isBedrockCraftingClick(
            true, InventoryType.CRAFTING, 4, false, false, InventoryAction.PICKUP_ALL
        ));
        assertFalse(MenuOpenEventHandler.isBedrockCraftingClick(
            true, InventoryType.CRAFTING, 5, false, false, InventoryAction.PICKUP_ALL
        ));
        assertFalse(MenuOpenEventHandler.isBedrockCraftingClick(
            false, InventoryType.CRAFTING, 0, false, false, InventoryAction.PICKUP_ALL
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-イベント.md
     * 章・見出し: # 09_3-イベント > ## 3. クラフト枠・画面ライフサイクル
     * 検証契約: BE プレイヤーの通常インベントリからクラフト枠へ向かう実際の Shift 移動だけを停止し、他の Shift クリックは一律停止しない。
     */
    @Test
    void blocksOnlyShiftTransferFromBottomInventory() {
        assertTrue(MenuOpenEventHandler.isBedrockCraftingClick(
            true, InventoryType.CRAFTING, 9, true, true, InventoryAction.MOVE_TO_OTHER_INVENTORY
        ));
        assertFalse(MenuOpenEventHandler.isBedrockCraftingClick(
            true, InventoryType.CRAFTING, 9, true, true, InventoryAction.PICKUP_ALL
        ));
        assertFalse(MenuOpenEventHandler.isBedrockCraftingClick(
            true, InventoryType.CRAFTING, 9, true, false, InventoryAction.MOVE_TO_OTHER_INVENTORY
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-イベント.md
     * 章・見出し: # 09_3-イベント > ## 3. クラフト枠・画面ライフサイクル
     * 検証契約: BE プレイヤーのドラッグがクラフト枠またはリザルト枠を含む場合だけ停止する。
     */
    @Test
    void blocksBedrockDragIntoCraftingShortcutAndResultSlots() {
        assertTrue(MenuOpenEventHandler.isBedrockCraftingDrag(
            true, InventoryType.CRAFTING, Set.of(5, 1)
        ));
        assertFalse(MenuOpenEventHandler.isBedrockCraftingDrag(
            true, InventoryType.CRAFTING, Set.of(5, 6)
        ));
        assertFalse(MenuOpenEventHandler.isBedrockCraftingDrag(
            false, InventoryType.CRAFTING, Set.of(0)
        ));
    }
}
