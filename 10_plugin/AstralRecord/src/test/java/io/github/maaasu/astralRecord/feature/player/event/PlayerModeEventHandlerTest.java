package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.party.gui.PartyGui;
import io.github.maaasu.astralRecord.feature.party.model.PartyInvite;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerModeEventHandlerTest extends MockBukkitTestBase {

    @AfterEach
    void clearCache() {
        AstPlayerCache.clear();
    }

    @Test
    void pluginGuiClickIsNotCancelledInPlayerMode() {
        PlayerModeEventHandler handler = new PlayerModeEventHandler();
        PlayerMock player = server().addPlayer();
        putPlayerModePlayer(player);
        Inventory topInventory = Bukkit.createInventory(new PluginGuiHolder(), 9);
        InventoryClickEvent event = mockClickEvent(player, topInventory);

        handler.onInventoryClick(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void nonPluginInventoryClickIsCancelledInPlayerMode() {
        PlayerModeEventHandler handler = new PlayerModeEventHandler();
        PlayerMock player = server().addPlayer();
        putPlayerModePlayer(player);
        InventoryHolder holder = mock(InventoryHolder.class);
        Inventory topInventory = Bukkit.createInventory(holder, 9);
        InventoryClickEvent event = mockClickEvent(player, topInventory);

        handler.onInventoryClick(event);

        verify(event).setCancelled(true);
    }

    @Test
    void partyGuiInventoryIsTreatedAsPluginGui() {
        PlayerModeEventHandler handler = new PlayerModeEventHandler();
        PlayerMock player = server().addPlayer();
        putPlayerModePlayer(player);
        PartyService partyService = mock(PartyService.class);
        when(partyService.findParty(player.getUniqueId())).thenReturn(null);
        when(partyService.getInvites(player.getUniqueId())).thenReturn(List.<PartyInvite>of());
        PartyGui partyGui = new PartyGui(partyService);
        partyGui.open(player);
        InventoryClickEvent event = mockClickEvent(player, player.getOpenInventory().getTopInventory());

        handler.onInventoryClick(event);

        verify(event, never()).setCancelled(true);
    }

    private void putPlayerModePlayer(Player player) {
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = new AccountModel(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "test-account",
            0,
            true,
            AccountMode.PLAYER,
            "{}",
            LocalDateTime.now(),
            LocalDateTime.now(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            false,
            1,
            0L
        );
        when(astPlayer.getBukkit()).thenReturn(player);
        when(astPlayer.getAccount()).thenReturn(account);
        AstPlayerCache.put(astPlayer);
    }

    private InventoryClickEvent mockClickEvent(Player player, Inventory topInventory) {
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(topInventory);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getView()).thenReturn(view);
        return event;
    }

    private static final class PluginGuiHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return Bukkit.createInventory(this, 9);
        }
    }
}
