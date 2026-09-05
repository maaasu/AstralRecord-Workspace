package io.github.maaasu.astralRecord.shared.gui.navigation;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class GuiNavigationServiceTest extends MockBukkitTestBase {

    @AfterEach
    void clearPlayerCache() {
        AstPlayerCache.clear();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 4. 共通 GUI navigation session
     * 検証契約: historyなしはcloseを示し、historyありは記録した直前GUIをopenする。
     */
    @Test
    void showsCloseWithoutHistoryAndOpensTheRecordedPreviousGui() {
        var player = server().addPlayer();
        AstPlayerCache.put(DesignTestFixtures.astPlayer(player, AccountMode.PLAYER));
        GuiNavigationService service = new GuiNavigationService(mock(AstralRecord.class));
        Inventory menu = inventory("menu");
        Inventory detail = inventory("detail");

        service.registerOpen(player, menu);

        assertEquals(Material.BARRIER, menu.getItem(49).getType());
        assertFalse(service.hasPrevious(player));

        service.registerOpen(player, detail);

        assertEquals(Material.SPECTRAL_ARROW, detail.getItem(49).getType());
        assertTrue(service.hasPrevious(player));
        assertTrue(service.openPrevious(player));
        assertSame(menu, player.getOpenInventory().getTopInventory());

        service.registerOpen(player, menu);
        assertFalse(service.hasPrevious(player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 4. 共通 GUI navigation session
     * 検証契約: back 遷移の target open が cancel された場合は、取り出した履歴を rollback し、現在画面を変更しない。
     */
    @Test
    void cancelledBackOpenRollsTheReservedHistoryBack() {
        var player = server().addPlayer();
        var astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        AstPlayerCache.put(astPlayer);
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.getServer()).thenReturn(server());
        GuiNavigationService service = new GuiNavigationService(plugin);
        Inventory menu = inventory("menu");
        Inventory detail = inventory("detail");
        player.openInventory(menu);
        service.registerOpen(player, menu);
        player.openInventory(detail);
        service.registerOpen(player, detail);
        server().getPluginManager().registerEvents(
            new CancelInventoryOpenListener(menu),
            MockBukkit.createMockPlugin("GuiNavigationCancellationTest")
        );

        AtomicBoolean opened = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();
        try (MockedStatic<JavaPlugin> javaPlugin = mockStatic(JavaPlugin.class)) {
            javaPlugin.when(() -> JavaPlugin.getPlugin(AstralRecord.class)).thenReturn(plugin);

            assertTrue(service.openPrevious(player, () -> opened.set(true), () -> cancelled.set(true)));
            assertSame(detail, player.getOpenInventory().getTopInventory());
            assertFalse(opened.get());
            assertFalse(cancelled.get());

            server().getScheduler().performTicks(2L);

            assertFalse(opened.get());
            assertTrue(cancelled.get());
            assertSame(detail, astPlayer.getGuiNavigationState().getCurrentGui());
            assertSame(menu, astPlayer.getGuiNavigationState().getPreviousGui());
            assertTrue(service.hasPrevious(player));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 4. 共通 GUI navigation session
     * 検証契約: 管理 GUI 間の戻る遷移は2 tick後に target を開き、表示成功 callback を一度だけ実行する。
     */
    @Test
    void delayedBackOpenRunsOpenedCallbackAfterTwoTicks() {
        var player = server().addPlayer();
        var astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        AstPlayerCache.put(astPlayer);
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.getServer()).thenReturn(server());
        GuiNavigationService service = new GuiNavigationService(plugin);
        Inventory menu = inventory("menu");
        Inventory detail = inventory("detail");
        player.openInventory(menu);
        service.registerOpen(player, menu);
        player.openInventory(detail);
        service.registerOpen(player, detail);
        AtomicBoolean opened = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();

        try (MockedStatic<JavaPlugin> javaPlugin = mockStatic(JavaPlugin.class)) {
            javaPlugin.when(() -> JavaPlugin.getPlugin(AstralRecord.class)).thenReturn(plugin);

            assertTrue(service.openPrevious(player, () -> opened.set(true), () -> cancelled.set(true)));
            assertSame(detail, player.getOpenInventory().getTopInventory());
            assertFalse(opened.get());
            server().getScheduler().performTicks(1L);
            assertFalse(opened.get());

            server().getScheduler().performTicks(1L);

            assertTrue(opened.get());
            assertFalse(cancelled.get());
            assertSame(menu, player.getOpenInventory().getTopInventory());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 4. 共通 GUI navigation session
     * 検証契約: always-close画面はhistoryがあってもback actionを公開しない。
     */
    @Test
    void alwaysCloseNavigationDoesNotExposeHistoryAsBackAction() {
        var player = server().addPlayer();
        AstPlayerCache.put(DesignTestFixtures.astPlayer(player, AccountMode.PLAYER));
        GuiNavigationService service = new GuiNavigationService(mock(AstralRecord.class));
        Inventory previous = inventory("previous");
        Inventory main = alwaysCloseInventory("main");

        service.registerOpen(player, previous);
        service.registerOpen(player, main);

        assertTrue(service.hasPrevious(player));
        assertTrue(service.isCloseNavigation(player, main));
        assertEquals(Material.BARRIER, main.getItem(49).getType());
    }

    private static Inventory inventory(String navigationId) {
        Inventory inventory = Bukkit.createInventory(new Holder(navigationId), 54);
        inventory.setItem(49, new ItemStack(Material.SPECTRAL_ARROW));
        return inventory;
    }

    private static Inventory alwaysCloseInventory(String navigationId) {
        Inventory inventory = Bukkit.createInventory(new AlwaysCloseHolder(navigationId), 54);
        inventory.setItem(49, new ItemStack(Material.SPECTRAL_ARROW));
        return inventory;
    }

    private record Holder(String navigationId) implements GuiNavigationHolder {
        @Override
        public @NotNull String getNavigationId() {
            return navigationId;
        }

        @Override
        public int getBackSlot() {
            return 49;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, 54);
        }
    }


    private record AlwaysCloseHolder(String navigationId) implements GuiNavigationHolder {
        @Override
        public @NotNull String getNavigationId() {
            return navigationId;
        }

        @Override
        public int getBackSlot() {
            return 49;
        }

        @Override
        public boolean isAlwaysCloseNavigation() {
            return true;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, 54);
        }
    }

    private static final class CancelInventoryOpenListener implements Listener {
        private final Inventory target;

        private CancelInventoryOpenListener(@NotNull Inventory target) {
            this.target = target;
        }

        @EventHandler
        public void onInventoryOpen(@NotNull InventoryOpenEvent event) {
            if (event.getInventory() == target) {
                event.setCancelled(true);
            }
        }
    }
}
