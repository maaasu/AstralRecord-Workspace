package io.github.maaasu.astralRecord.shared.gui.hotbar;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryClickGuard;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.menu.player.PlayerBrowserGuiEventHandler;
import io.github.maaasu.astralRecord.feature.menu.player.PlayerDetailGui;
import io.github.maaasu.astralRecord.feature.menu.player.PlayerListBackTarget;
import io.github.maaasu.astralRecord.feature.menu.player.PlayerListGui;
import io.github.maaasu.astralRecord.feature.menu.player.PlayerListPurpose;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.party.event.PartyGuiEventHandler;
import io.github.maaasu.astralRecord.feature.party.gui.PartyGui;
import io.github.maaasu.astralRecord.feature.party.gui.PartyMemberActionGui;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.shop.event.ShopGuiEventHandler;
import io.github.maaasu.astralRecord.feature.shop.gui.ShopGui;
import io.github.maaasu.astralRecord.feature.shop.service.ShopService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HotbarShortcutGuiIntegrationMockBukkitTest extends MockBukkitTestBase {

    @AfterEach
    void clearAstPlayerCache() {
        AstPlayerCache.clear();
    }

    @Test
    void shopPartyAndPlayerListInventoriesAreManagedHotbarShortcutGuis() {
        PlayerMock player = server().addPlayer();
        PartyService partyService = mock(PartyService.class);
        when(partyService.getInvites(player.getUniqueId())).thenReturn(List.of());
        PartyGui partyGui = new PartyGui(partyService);
        PlayerListGui playerListGui = new PlayerListGui();

        Inventory shopList = new ShopGui.ListHolder("test_shop", 0).getInventory();
        Inventory shopConfirm = new ShopGui.ConfirmHolder("test_shop", "entry", 1, 0).getInventory();
        partyGui.open(player);
        Inventory partyInventory = player.getOpenInventory().getTopInventory();
        playerListGui.open(
            player,
            PlayerListPurpose.PLAYER_INFO,
            PlayerListBackTarget.MENU,
            "players",
            List.of(player.getUniqueId()),
            0
        );
        Inventory playerListInventory = player.getOpenInventory().getTopInventory();

        assertTrue(HotbarShortcutGuiSupport.isManagedGui(shopList));
        assertTrue(HotbarShortcutGuiSupport.isManagedGui(shopConfirm));
        assertTrue(HotbarShortcutGuiSupport.isManagedGui(partyInventory));
        assertTrue(HotbarShortcutGuiSupport.isManagedGui(playerListInventory));
    }

    @Test
    void shopGuiHotbarCycleClickReachesCommonShortcutHandling() {
        PlayerMock player = playerWithAstPlayer();
        InventoryService inventoryService = inventoryServiceForHotbarMode(AstPlayerCache.get(player));
        ShopGui shopGui = mock(ShopGui.class);
        Inventory inventory = Bukkit.createInventory(new ShopGui.ListHolder("test_shop", 0), ShopGui.LIST_SIZE);
        player.openInventory(inventory);
        when(shopGui.isListInventory(inventory)).thenReturn(true);
        ShopGuiEventHandler handler = new ShopGuiEventHandler(shopGui, mock(ShopService.class), inventoryService);

        handler.onInventoryClick(hotbarClick(player, inventory, 8));

        verify(inventoryService).handleHotbarShortcutClick(AstPlayerCache.get(player), 8);
    }

    @Test
    void partyGuiHotbarCycleClickReachesCommonShortcutHandling() {
        PlayerMock player = playerWithAstPlayer();
        InventoryService inventoryService = inventoryServiceForHotbarMode(AstPlayerCache.get(player));
        PartyService partyService = mock(PartyService.class);
        when(partyService.getInvites(player.getUniqueId())).thenReturn(List.of());
        PartyGui partyGui = new PartyGui(partyService);
        partyGui.open(player);
        PlayerBrowserGuiEventHandler playerBrowser = new PlayerBrowserGuiEventHandler(
            mock(AstralRecord.class),
            new PlayerListGui(),
            new PlayerDetailGui(),
            partyService,
            mock(StatusService.class),
            mock(MenuView.class),
            inventoryService
        );
        PartyGuiEventHandler handler = new PartyGuiEventHandler(
            partyGui,
            new PartyMemberActionGui(),
            partyService,
            mock(MenuView.class),
            playerBrowser,
            inventoryService
        );

        handler.onInventoryClick(hotbarClick(player, player.getOpenInventory().getTopInventory(), 8));

        verify(inventoryService).handleHotbarShortcutClick(AstPlayerCache.get(player), 8);
    }

    @Test
    void playerListGuiHotbarCycleClickReachesCommonShortcutHandling() {
        PlayerMock player = playerWithAstPlayer();
        InventoryService inventoryService = inventoryServiceForHotbarMode(AstPlayerCache.get(player));
        PlayerListGui playerListGui = new PlayerListGui();
        playerListGui.open(
            player,
            PlayerListPurpose.PLAYER_INFO,
            PlayerListBackTarget.MENU,
            "players",
            List.of(player.getUniqueId()),
            0
        );
        PlayerBrowserGuiEventHandler handler = new PlayerBrowserGuiEventHandler(
            mock(AstralRecord.class),
            playerListGui,
            new PlayerDetailGui(),
            mock(PartyService.class),
            mock(StatusService.class),
            mock(MenuView.class),
            inventoryService
        );

        handler.onInventoryClick(hotbarClick(player, player.getOpenInventory().getTopInventory(), 8));

        verify(inventoryService).handleHotbarShortcutClick(AstPlayerCache.get(player), 8);
    }

    private static InventoryClickEvent hotbarClick(PlayerMock player, Inventory topInventory, int slot) {
        return new PlayerHotbarClickEvent(player, topInventory, slot);
    }

    private static final class PlayerHotbarClickEvent extends InventoryClickEvent {
        private final PlayerMock player;
        private final Inventory topInventory;
        private final int slot;
        private boolean cancelled;

        private PlayerHotbarClickEvent(PlayerMock player, Inventory topInventory, int slot) {
            super(player.getOpenInventory(), InventoryType.SlotType.QUICKBAR, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            this.player = player;
            this.topInventory = topInventory;
            this.slot = slot;
        }

        @Override
        public InventoryView getView() {
            return player.getOpenInventory();
        }

        @Override
        public PlayerMock getWhoClicked() {
            return player;
        }

        @Override
        public Inventory getClickedInventory() {
            return player.getInventory();
        }

        @Override
        public int getSlot() {
            return slot;
        }

        @Override
        public int getRawSlot() {
            return topInventory.getSize() + slot;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }
    }

    private static InventoryService inventoryServiceForHotbarMode(AstPlayer astPlayer) {
        InventoryService inventoryService = mock(InventoryService.class);
        when(inventoryService.getClickGuard()).thenReturn(new InventoryClickGuard());
        when(inventoryService.isHotbarShortcutMode(astPlayer)).thenReturn(true);
        when(inventoryService.handleHotbarShortcutClick(astPlayer, 8)).thenReturn(true);
        return inventoryService;
    }

    private PlayerMock playerWithAstPlayer() {
        PlayerMock player = server().addPlayer();
        player.getInventory().setItem(8, new org.bukkit.inventory.ItemStack(Material.CHEST));
        AstPlayer astPlayer = astPlayer(player);
        AstPlayerCache.put(astPlayer);
        return player;
    }

    private static AstPlayer astPlayer(PlayerMock player) {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID systemId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        UserModel user = new UserModel(
            userId,
            player.getName(),
            now,
            now,
            "127.0.0.1",
            accountId,
            false,
            null,
            false,
            0,
            now,
            now,
            systemId,
            systemId,
            false
        );
        AccountModel account = new AccountModel(
            accountId,
            userId,
            "test",
            0,
            true,
            AccountMode.PLAYER,
            "{}",
            now,
            now,
            systemId,
            systemId,
            false
        );
        return new AstPlayer(player, user, account);
    }
}
