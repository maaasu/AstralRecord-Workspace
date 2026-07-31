package io.github.maaasu.astralRecord.shared.gui;

import io.github.maaasu.astralRecord.shared.gui.sound.GuiCloseSoundPolicy;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuiOpenSupportTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI 遷移ガード
     * 検証契約: plugin GUI遷移時は遷移元close soundだけを1回抑止する。
     */
    @Test
    void pluginGuiTransitionSuppressesOnlyTheSourceCloseSound() {
        Player player = mock(Player.class);
        InventoryView view = mock(InventoryView.class);
        Inventory source = mock(Inventory.class);
        Inventory target = mock(Inventory.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(source);

        GuiOpenSupport.openIfSourceIsCurrent(player, source, target);

        verify(player).openInventory(target);
        assertFalse(GuiCloseSoundPolicy.shouldPlayCloseSound(player, source));
        assertTrue(GuiCloseSoundPolicy.shouldPlayCloseSound(player, target));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI 遷移ガード
     * 検証契約: 遷移openが成立しない場合はclose sound抑止状態を残さない。
     */
    @Test
    void cancelledTransitionDoesNotSuppressCloseSound() {
        Player player = mock(Player.class);
        InventoryView view = mock(InventoryView.class);
        Inventory source = mock(Inventory.class);
        Inventory other = mock(Inventory.class);
        Inventory target = mock(Inventory.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(other);

        GuiOpenSupport.openIfSourceIsCurrent(player, source, target);

        verify(player, never()).openInventory(target);
        assertTrue(GuiCloseSoundPolicy.shouldPlayCloseSound(player, source));
    }
}
