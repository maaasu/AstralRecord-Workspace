package io.github.maaasu.astralRecord.shared.gui.event;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.gui.OrbGuiHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuiClickCooldownEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: Orb GUI はAPI操作・正本照合中の専用ロックだけで制御し、共通hotbar shortcut/cooldown処理へ渡さない。
     */
    @Test
    void orbGuiBypassesSharedHotbarShortcutAndCooldown() {
        InventoryService inventoryService = mock(InventoryService.class);
        GuiClickCooldownEventHandler handler = new GuiClickCooldownEventHandler(inventoryService);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        Player player = mock(Player.class);
        InventoryView view = mock(InventoryView.class);
        Inventory top = mock(Inventory.class);
        OrbGuiHolder holder = new OrbGuiHolder(UUID.randomUUID(), UUID.randomUUID(), OrbGuiHolder.Screen.LIST);

        when(event.getWhoClicked()).thenReturn(player);
        when(event.getView()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(top);
        when(top.getHolder()).thenReturn(holder);

        handler.onInventoryClick(event);

        verify(event, never()).getClickedInventory();
        verify(event, never()).setCancelled(true);
        verify(inventoryService, never()).isHotbarShortcutMode(org.mockito.ArgumentMatchers.any());
    }
}
