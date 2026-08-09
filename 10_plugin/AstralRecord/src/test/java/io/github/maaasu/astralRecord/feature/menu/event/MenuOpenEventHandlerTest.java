package io.github.maaasu.astralRecord.feature.menu.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.currency.event.CurrencyExchangeGuiEventHandler;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
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
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MenuOpenEventHandlerTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-イベント.md
     * 章・見出し: # 09_3-イベント > ## 2. クラフト枠・画面ライフサイクル
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

    private MenuOpenEventHandler newMenuHandler(
        @NotNull TrashService trashService,
        @NotNull SellService sellService,
        @NotNull StorageService storageService,
        @NotNull MenuGuiTransitionService menuGuiTransitionService,
        @NotNull InventoryService inventoryService
    ) {
        AstralRecord menuPlugin = mock(AstralRecord.class);
        Server menuServer = mock(Server.class);
        BukkitScheduler menuScheduler = mock(BukkitScheduler.class);
        when(menuPlugin.getServer()).thenReturn(menuServer);
        when(menuServer.getScheduler()).thenReturn(menuScheduler);
        return new MenuOpenEventHandler(
            menuPlugin,
            mock(MenuView.class),
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
