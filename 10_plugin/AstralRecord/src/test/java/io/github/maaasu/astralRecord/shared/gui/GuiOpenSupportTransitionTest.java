package io.github.maaasu.astralRecord.shared.gui;

import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionTransitionService;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuiOpenSupportTransitionTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: 統合版の既存コンテナは明示closeの後にtargetを開き、同tickの成功で旧close候補を無効にする。
     */
    @Test
    void bedrockReplacementClosesBeforeOpeningAndPreservesSession() {
        Fixture f = new Fixture(true, false);
        GuiSessionTransitionService sessions = new GuiSessionTransitionService();
        sessions.registerOpened(f.player.getUniqueId(), f.source);
        AtomicReference<GuiSessionTransitionService.CloseToken> close = new AtomicReference<>();
        doAnswer(call -> {
            close.set(sessions.beginClose(f.player.getUniqueId(), f.source));
            f.current.set(f.neutral);
            return null;
        }).when(f.player).closeInventory(InventoryCloseEvent.Reason.OPEN_NEW);
        try (var title = mockStatic(GuiResourcePackTitle.class)) {
            title.when(() -> GuiResourcePackTitle.open(f.player, f.target)).thenAnswer(call -> {
                assertSame(f.neutral, f.current.get());
                assertNotNull(close.get());
                f.current.set(f.target);
                sessions.registerOpened(f.player.getUniqueId(), f.target);
                return f.view;
            });
            assertTrue(f.open(f.target));
            verify(f.player, times(1)).closeInventory(InventoryCloseEvent.Reason.OPEN_NEW);
            assertNull(sessions.finishClose(f.player.getUniqueId(), close.get()));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: Java版の画面置換では追加の明示closeを送らず、成功したtargetを保持する。
     */
    @Test
    void javaReplacementDoesNotAddAnExplicitClose() {
        assertOpensWithoutClose(new Fixture(false, false));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: 統合版の通常インベントリからの初回表示では追加closeを送らない。
     */
    @Test
    void bedrockInitialOpenDoesNotCloseThePlayerInventory() {
        assertOpensWithoutClose(new Fixture(true, true));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: 遷移元が既に変わった場合とログアウト済みの場合は、旧要求で画面を閉じたり開いたりしない。
     */
    @Test
    void obsoleteSourceAndOfflinePlayerCannotCloseOrOpenInventories() {
        for (boolean offline : new boolean[]{false, true}) {
            Fixture f = new Fixture(true, false);
            if (offline) when(f.player.isOnline()).thenReturn(false);
            else f.current.set(f.neutral);
            try (var title = mockStatic(GuiResourcePackTitle.class)) {
                assertFalse(f.open(f.target));
                verify(f.player, never()).closeInventory(any(InventoryCloseEvent.Reason.class));
                title.verifyNoInteractions();
            }
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: 明示close中に別GUIへ遷移した場合または切断した場合は、予約していたtargetで上書きしない。
     */
    @Test
    void closeHandlerRedirectAndDisconnectCannotBeOverwritten() {
        for (boolean offline : new boolean[]{false, true}) {
            Fixture f = new Fixture(true, false);
            Inventory redirected = mock(Inventory.class);
            doAnswer(call -> {
                f.current.set(offline ? f.neutral : redirected);
                if (offline) when(f.player.isOnline()).thenReturn(false);
                return null;
            }).when(f.player).closeInventory(InventoryCloseEvent.Reason.OPEN_NEW);
            try (var title = mockStatic(GuiResourcePackTitle.class)) {
                assertFalse(f.open(f.target));
                title.verifyNoInteractions();
                assertSame(offline ? f.neutral : redirected, f.current.get());
            }
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: 明示close後にtarget表示が拒否された場合、遷移を成功として扱わない。
     */
    @Test
    void rejectedTargetDoesNotReportAnOpenedTransition() {
        Fixture f = new Fixture(true, false);
        try (var title = mockStatic(GuiResourcePackTitle.class)) {
            assertFalse(f.open(f.target));
            assertSame(f.neutral, f.current.get());
            title.verify(() -> GuiResourcePackTitle.open(f.player, f.target), times(1));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: 統合版でも同じinventoryインスタンスの再表示に追加closeを送らない。
     */
    @Test
    void sameInventoryRefreshDoesNotAddAnExplicitClose() {
        Fixture f = new Fixture(true, false);
        try (var title = mockStatic(GuiResourcePackTitle.class)) {
            assertTrue(f.open(f.source));
            verify(f.player, never()).closeInventory(any(InventoryCloseEvent.Reason.class));
            title.verify(() -> GuiResourcePackTitle.open(f.player, f.source), times(1));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: close中に後続の表示予約が作られた場合は旧targetを開かず、後続予約を保持する。
     */
    @Test
    void newerRequestCreatedDuringCloseSupersedesTheTarget() throws Exception {
        Fixture f = new Fixture(true, false);
        var field = GuiOpenSupport.class.getDeclaredField("PENDING_TRANSITIONS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<UUID, Object> pending = (java.util.Map<UUID, Object>) field.get(null);
        Class<?> type = Class.forName(GuiOpenSupport.class.getName() + "$PendingTransition");
        var constructor = type.getDeclaredConstructor(Runnable.class, Runnable.class);
        constructor.setAccessible(true);
        Object newer = constructor.newInstance(mock(Runnable.class), mock(Runnable.class));
        doAnswer(call -> {
            f.current.set(f.neutral);
            pending.put(f.player.getUniqueId(), newer);
            return null;
        }).when(f.player).closeInventory(InventoryCloseEvent.Reason.OPEN_NEW);
        try (var title = mockStatic(GuiResourcePackTitle.class)) {
            assertFalse(f.open(f.target));
            assertSame(newer, pending.get(f.player.getUniqueId()));
            title.verifyNoInteractions();
        } finally {
            pending.remove(f.player.getUniqueId());
        }
    }

    private static void assertOpensWithoutClose(Fixture f) {
        try (var title = mockStatic(GuiResourcePackTitle.class)) {
            title.when(() -> GuiResourcePackTitle.open(f.player, f.target)).thenAnswer(call -> {
                f.current.set(f.target);
                return f.view;
            });
            assertTrue(f.open(f.target));
            verify(f.player, never()).closeInventory(any(InventoryCloseEvent.Reason.class));
        }
    }

    /** 疑似サーバーを起動せず、共通画面遷移の状態とAPI境界だけを再現します。 */
    private static final class Fixture {
        private final Player player = mock(Player.class);
        private final Inventory source = mock(Inventory.class);
        private final Inventory target = mock(Inventory.class);
        private final Inventory neutral = mock(Inventory.class);
        private final InventoryView view = mock(InventoryView.class);
        private final AtomicReference<Inventory> current = new AtomicReference<>(source);

        private final boolean bedrock;
        private final boolean playerInventory;

        private Fixture(boolean bedrock, boolean playerInventory) {
            this.bedrock = bedrock;
            this.playerInventory = playerInventory;
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.isOnline()).thenReturn(true);
            when(player.getOpenInventory()).thenReturn(view);
            when(view.getTopInventory()).thenAnswer(call -> current.get());
            doAnswer(call -> {
                current.set(neutral);
                return null;
            }).when(player).closeInventory(InventoryCloseEvent.Reason.OPEN_NEW);
        }

        private boolean open(Inventory destination) {
            return GuiOpenSupport.openIfSourceIsCurrent(player, source, destination, bedrock,
                inventory -> inventory == neutral || (inventory == source && playerInventory));
        }
    }
}
