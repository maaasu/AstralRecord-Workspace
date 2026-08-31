package io.github.maaasu.astralRecord.feature.menu.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.currency.event.CurrencyExchangeGuiEventHandler;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.menu.service.MenuGuiTransitionService;
import io.github.maaasu.astralRecord.feature.menu.service.PlayerGuiRenderContextFactory;
import io.github.maaasu.astralRecord.feature.menu.service.TrashService;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.sell.service.SellService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.storage.service.StorageService;
import io.github.maaasu.astralRecord.feature.world.service.ReturnToBaseService;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionTransitionEventHandler;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionTransitionService;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MenuOpenEventHandlerTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-イベント.md
     * 章・見出し: # 09_3-イベント > ## 1. メニューアイテム右クリック入力解決
     * 検証契約: RIGHT_CLICK の EQUIPMENT / TOOL / MAIN_MENU アイテムは、元入力をキャンセルするメインメニュー候補へ解決される。
     */
    @Test
    void rightClickMainMenuToolReturnsMenuCandidate() {
        InventoryService inventoryService = mock(InventoryService.class);
        MenuOpenEventHandler menuHandler = newMenuHandler(
            mock(TrashService.class),
            mock(SellService.class),
            mock(StorageService.class),
            mock(MenuGuiTransitionService.class),
            inventoryService
        );
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        ItemModel menuItem = mock(ItemModel.class);
        ItemEquipment equipment = mock(ItemEquipment.class);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        when(inventoryService.getItemModelInHand(astPlayer, EquipmentSlot.HAND)).thenReturn(menuItem);
        when(menuItem.getId()).thenReturn("nox_menu_tool");
        when(menuItem.getCategory()).thenReturn("equipment");
        when(menuItem.getEquipment()).thenReturn(equipment);
        when(equipment.getSlot()).thenReturn(ItemEquipmentSlot.TOOL);
        when(equipment.getTag()).thenReturn("MAIN_MENU");
        PlayerInteractionRayTrace ray = PlayerInteractionRayTrace.create(
            new Vector(0.0D, 0.0D, 0.0D),
            new Vector(0.0D, 0.0D, 1.0D),
            8.0D
        );
        PlayerInteractionSnapshot snapshot = new PlayerInteractionSnapshot(
            player,
            mock(Event.class),
            EquipmentSlot.HAND,
            null,
            null,
            null,
            null,
            false,
            ray,
            8.0D
        );
        PlayerInputContext<PlayerInteractionSnapshot> context = new PlayerInputContext<>(
            player.getUniqueId(),
            0L,
            InputFamily.RIGHT_CLICK,
            InputSource.PLAYER_INTERACT,
            snapshot
        );

        try (var cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            var candidates = menuHandler.resolve(context);

            assertEquals(1, candidates.size());
            assertEquals("menu-tool-open", candidates.get(0).id());
            assertEquals(InteractionTier.ITEM_USE, candidates.get(0).tier());
            assertEquals(InputClaimPolicy.CLAIM_AND_CANCEL, candidates.get(0).claimPolicy());
            assertNotNull(candidates.get(0).executor());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-イベント.md
     * 章・見出し: # 09_3-イベント > ## 3. クラフト枠・画面ライフサイクル
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: ログアウトの音なし session end は Trash、Sell、Storage、dummy inventory、hotbar shortcut の終了 cleanup を一度ずつ実行し、CLOSE 音を再生しない。
     */
    @Test
    void silentQuitRunsAllSharedMenuCleanupExactlyOnce() {
        PluginMock registrationPlugin = MockBukkit.createMockPlugin("MenuOpenEventHandlerTest");
        AstralRecord lifecyclePlugin = mock(AstralRecord.class);
        when(lifecyclePlugin.getServer()).thenReturn(server());
        GuiSessionTransitionEventHandler lifecycleHandler = new GuiSessionTransitionEventHandler(
            lifecyclePlugin,
            new GuiSessionTransitionService()
        );
        server().getPluginManager().registerEvents(lifecycleHandler, registrationPlugin);

        TrashService trashService = mock(TrashService.class);
        SellService sellService = mock(SellService.class);
        StorageService storageService = mock(StorageService.class);
        MenuGuiTransitionService menuGuiTransitionService = mock(MenuGuiTransitionService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        MenuOpenEventHandler menuHandler = newMenuHandler(
            trashService,
            sellService,
            storageService,
            menuGuiTransitionService,
            inventoryService
        );
        server().getPluginManager().registerEvents(menuHandler, registrationPlugin);

        PlayerMock player = server().addPlayer();
        Inventory source = managedInventory();
        AstPlayer astPlayer = mock(AstPlayer.class);
        PlayerQuitEvent quitEvent = mock(PlayerQuitEvent.class);
        when(quitEvent.getPlayer()).thenReturn(player);
        when(menuGuiTransitionService.consumePlayerInventoryDummyApplied(player)).thenReturn(true);
        when(menuGuiTransitionService.consumeSuppressedPlayerInventoryRestore(player)).thenReturn(false);

        player.openInventory(source);
        try (var cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            lifecycleHandler.onPlayerQuit(quitEvent);
            server().getScheduler().performOneTick();
        }

        verify(trashService, times(1)).handleClose(source, player);
        verify(sellService, times(1)).handleClose(source, player);
        verify(storageService, times(1)).handleClose(player, source);
        verify(menuGuiTransitionService, times(1)).restorePlayerInventory(player);
        verify(inventoryService, times(1)).setHotbarShortcutMode(astPlayer, false);
        assertEquals(0L, closeSoundCount(player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 6. クラフトショートカット描画
     * 検証契約: join 時に shortcut が残っていない場合は、cleanup が inventory 全体同期を発生させない。
     */
    @Test
    void joinCleanupDoesNotUpdateInventoryWhenNoShortcutExists() {
        MenuView menuView = mock(MenuView.class);
        InventoryService inventoryService = mock(InventoryService.class);
        MenuOpenEventHandler menuHandler = newMenuHandler(
            menuView,
            mock(TrashService.class),
            mock(SellService.class),
            mock(StorageService.class),
            mock(MenuGuiTransitionService.class),
            inventoryService
        );
        Player player = mock(Player.class);
        World world = mock(World.class);
        PlayerJoinEvent joinEvent = mock(PlayerJoinEvent.class);
        when(joinEvent.getPlayer()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(world.getEntitiesByClass(Item.class)).thenReturn(java.util.List.of());

        menuHandler.onPlayerJoin(joinEvent);

        verify(menuView).clearCraftShortcuts(player);
        verify(menuView).removeCraftShortcutItems(player);
        verify(player, never()).updateInventory();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 6. クラフトショートカット描画
     * 検証契約: BE版の再描画では、クラフト欄だけでなくプレイヤー所持品とカーソル上の shortcut も除去し、変更時だけ同期する。
     */
    @Test
    void bedrockCraftShortcutRefreshRemovesAllShortcutLocations() {
        MenuView menuView = mock(MenuView.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        InventoryService inventoryService = mock(InventoryService.class);
        MenuOpenEventHandler menuHandler = newMenuHandler(
            menuView,
            scheduler,
            mock(TrashService.class),
            mock(SellService.class),
            mock(StorageService.class),
            mock(MenuGuiTransitionService.class),
            inventoryService
        );
        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        when(astPlayer.isBedrock()).thenReturn(true);
        when(menuView.clearCraftShortcuts(player)).thenReturn(false);
        when(menuView.removeCraftShortcutItems(player)).thenReturn(true);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        try (var cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            menuHandler.refreshCraftShortcuts(player);
            verify(scheduler).runTask(any(AstralRecord.class), taskCaptor.capture());
            taskCaptor.getValue().run();
        }

        verify(menuView).clearCraftShortcuts(player);
        verify(menuView).removeCraftShortcutItems(player);
        verify(player).updateInventory();
    }

    private MenuOpenEventHandler newMenuHandler(
        @NotNull TrashService trashService,
        @NotNull SellService sellService,
        @NotNull StorageService storageService,
        @NotNull MenuGuiTransitionService menuGuiTransitionService,
        @NotNull InventoryService inventoryService
    ) {
        return newMenuHandler(
            mock(MenuView.class),
            mock(BukkitScheduler.class),
            trashService,
            sellService,
            storageService,
            menuGuiTransitionService,
            inventoryService
        );
    }

    private MenuOpenEventHandler newMenuHandler(
        @NotNull MenuView menuView,
        @NotNull TrashService trashService,
        @NotNull SellService sellService,
        @NotNull StorageService storageService,
        @NotNull MenuGuiTransitionService menuGuiTransitionService,
        @NotNull InventoryService inventoryService
    ) {
        return newMenuHandler(
            menuView,
            mock(BukkitScheduler.class),
            trashService,
            sellService,
            storageService,
            menuGuiTransitionService,
            inventoryService
        );
    }

    private MenuOpenEventHandler newMenuHandler(
        @NotNull MenuView menuView,
        @NotNull BukkitScheduler menuScheduler,
        @NotNull TrashService trashService,
        @NotNull SellService sellService,
        @NotNull StorageService storageService,
        @NotNull MenuGuiTransitionService menuGuiTransitionService,
        @NotNull InventoryService inventoryService
    ) {
        AstralRecord menuPlugin = mock(AstralRecord.class);
        Server menuServer = mock(Server.class);
        when(menuPlugin.getServer()).thenReturn(menuServer);
        when(menuServer.getScheduler()).thenReturn(menuScheduler);
        return new MenuOpenEventHandler(
            menuPlugin,
            menuView,
            inventoryService,
            mock(CurrencyService.class),
            mock(CurrencyExchangeGuiEventHandler.class),
            mock(StatusService.class),
            mock(PlayerGuiRenderContextFactory.class),
            menuGuiTransitionService,
            trashService,
            sellService,
            storageService,
            mock(ReturnToBaseService.class)
        );
    }

    private static Inventory managedInventory() {
        TestGuiHolder holder = new TestGuiHolder();
        Inventory inventory = Bukkit.createInventory(holder, 9);
        holder.setInventory(inventory);
        return inventory;
    }

    private static long closeSoundCount(@NotNull PlayerMock player) {
        return player.getHeardSounds().stream()
            .filter(sound -> Registry.SOUND_EVENT.getKeyOrThrow(Sound.BLOCK_CHEST_CLOSE).getKey().equals(sound.getSound()))
            .count();
    }

    private static final class TestGuiHolder implements HotbarShortcutGuiHolder {
        private Inventory inventory;

        private void setInventory(@NotNull Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
