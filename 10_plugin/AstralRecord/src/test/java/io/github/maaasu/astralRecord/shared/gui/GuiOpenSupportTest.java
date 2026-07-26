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
