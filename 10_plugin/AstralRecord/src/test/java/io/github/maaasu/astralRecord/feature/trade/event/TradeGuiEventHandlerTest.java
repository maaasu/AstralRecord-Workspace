package io.github.maaasu.astralRecord.feature.trade.event;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeGui;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeGuiLayout;
import io.github.maaasu.astralRecord.feature.trade.model.TradeSession;
import io.github.maaasu.astralRecord.feature.trade.service.TradeService;
import io.github.maaasu.astralRecord.shared.gui.gold.GoldAmountSettingGui;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeGuiEventHandlerTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 送信画面の開始・終了
     * 検証契約: 送信画面 holder は共通ホットバーショートカットの対象になる。
     */
    @Test
    void tradeHolderUsesSharedHotbarShortcutContract() {
        assertInstanceOf(HotbarShortcutGuiHolder.class,
            new TradeGui.TradeHolder(UUID.randomUUID(), UUID.randomUUID()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## アイテム予約
     * 検証契約: 送信画面下部の所有 item click は標準移動を中止し、Bukkit slot と click 種別を予約操作へ渡す。
     */
    @Test
    void cancelsVanillaMovementAndDelegatesBottomSlotOfferToTradeService() {
        TestContext context = new TestContext();
        InventoryClickEvent event = context.clickEvent(54, 9, ClickType.LEFT);
        PlayerInventory playerInventory = mock(PlayerInventory.class);
        ItemStack displayed = mock(ItemStack.class);
        when(event.getClickedInventory()).thenReturn(playerInventory);
        when(event.getCurrentItem()).thenReturn(displayed);
        when(displayed.getType()).thenReturn(Material.STONE);
        when(context.tradeService.isTradeable(displayed)).thenReturn(true);
        when(context.tradeService.offerOwnedItem(context.player, 9, ClickType.LEFT, displayed)).thenReturn(true);

        invokeTradeClick(context, event);

        verify(event).setCancelled(true);
        verify(context.tradeService).offerOwnedItem(context.player, 9, ClickType.LEFT, displayed);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 送信画面の開始・終了
     * 検証契約: 送信ボタンは現在の送信者 session だけへ send を委譲する。
     */
    @Test
    void sendButtonDelegatesToSenderOnlySend() {
        TestContext context = new TestContext();
        invokeTradeClick(context, context.clickEvent(TradeGuiLayout.SEND_SLOT, TradeGuiLayout.SEND_SLOT, ClickType.LEFT));
        verify(context.tradeService).send(context.player);
        verify(context.tradeService, never()).leave(context.player, false);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 送信画面の開始・終了
     * 検証契約: 戻るボタンは予約を解除し、開始元画面へ戻る leave を要求する。
     */
    @Test
    void backButtonLeavesAndRequestsReturnAction() {
        TestContext context = new TestContext();
        invokeTradeClick(context, context.clickEvent(TradeGuiLayout.BACK_SLOT, TradeGuiLayout.BACK_SLOT, ClickType.LEFT));
        verify(context.tradeService).leave(context.player, true);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 送信画面の開始・終了
     * 検証契約: 閉じるボタンは予約を解除し、開始元画面へ戻らない leave を要求する。
     */
    @Test
    void closeButtonLeavesWithoutReturnAction() {
        TestContext context = new TestContext();
        invokeTradeClick(context, context.clickEvent(TradeGuiLayout.CLOSE_SLOT, TradeGuiLayout.CLOSE_SLOT, ClickType.LEFT));
        verify(context.tradeService).leave(context.player, false);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 送信画面の開始・終了
     * 検証契約: 金額スロットは送信者の金額設定画面だけを開く。
     */
    @Test
    void goldButtonDelegatesToSendersGoldAmountSetting() {
        TestContext context = new TestContext();
        invokeTradeClick(context, context.clickEvent(TradeGuiLayout.GOLD_SLOT, TradeGuiLayout.GOLD_SLOT, ClickType.LEFT));
        verify(context.tradeService).openGoldAmountSetting(context.player);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## アイテム予約
     * 検証契約: 送信画面上の drag はすべて中止し、予約済み表示を標準 inventory 操作で変更させない。
     */
    @Test
    void cancelsEveryDragOverSendInventory() {
        TestContext context = new TestContext();
        InventoryDragEvent event = mock(InventoryDragEvent.class);
        when(event.getView()).thenReturn(context.view);
        when(event.getWhoClicked()).thenReturn(context.player);

        try (var guard = mockStatic(AccountModeGuard.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(context.player)).thenReturn(true);
            context.handler.onInventoryDrag(event);
        }
        verify(event).setCancelled(true);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 送信画面の開始・終了
     * 検証契約: 現在の送信画面を手動で閉じると、内部遷移でない限り送信者の予約を直ちに中止する。
     */
    @Test
    void manualCloseOfCurrentSendViewCancelsSendersDraft() {
        TestContext context = new TestContext();
        InventoryCloseEvent event = mock(InventoryCloseEvent.class);
        when(event.getPlayer()).thenReturn(context.player);
        when(event.getInventory()).thenReturn(context.top);
        context.handler.onInventoryClose(event);
        verify(context.tradeService).cancelTrade(context.player);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 送信画面の開始・終了
     * 検証契約: 古い session の送信画面 close は現在の送信者 draft を中止しない。
     */
    @Test
    void ignoresCloseFromSendViewWhoseHolderDoesNotMatchCurrentSession() {
        TestContext context = new TestContext();
        InventoryCloseEvent event = mock(InventoryCloseEvent.class);
        when(event.getPlayer()).thenReturn(context.player);
        when(event.getInventory()).thenReturn(context.top);
        when(context.tradeGui.getTradeHolder(context.top)).thenReturn(
            new TradeGui.TradeHolder(UUID.randomUUID(), context.playerId));
        context.handler.onInventoryClose(event);
        verify(context.tradeService, never()).cancelTrade(context.player);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 送信画面の開始・終了
     * 検証契約: 許可対象外ワールドへの移動とログアウトは、当人に関連する未確定送信を中止する。
     */
    @Test
    void worldDepartureAndQuitCancelRelatedSessions() {
        TestContext context = new TestContext();
        PlayerChangedWorldEvent changedWorld = mock(PlayerChangedWorldEvent.class);
        when(changedWorld.getPlayer()).thenReturn(context.player);
        when(context.tradeService.isTradeAllowedWorld(context.player)).thenReturn(false);
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(context.player);
        context.handler.onPlayerChangedWorld(changedWorld);
        context.handler.onPlayerQuit(quit);
        verify(context.tradeService, times(2)).cancelRelatedSessions(context.player);
    }

    private static void invokeTradeClick(TestContext context, InventoryClickEvent event) {
        try (var guard = mockStatic(AccountModeGuard.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(context.player)).thenReturn(true);
            context.handler.onInventoryClick(event);
        }
    }

    private static final class TestContext {
        private final TradeGui tradeGui = mock(TradeGui.class);
        private final GoldAmountSettingGui goldAmountSettingGui = mock(GoldAmountSettingGui.class);
        private final TradeService tradeService = mock(TradeService.class);
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final PlayerMessageService messageService = mock(PlayerMessageService.class);
        private final Player player = mock(Player.class);
        private final UUID playerId = UUID.randomUUID();
        private final UUID sessionId = UUID.randomUUID();
        private final TradeSession currentSession = tradeSession(sessionId, playerId);
        private final Inventory top = mock(Inventory.class);
        private final InventoryView view = mock(InventoryView.class);
        private final TradeGuiEventHandler handler;

        private TestContext() {
            when(view.getTopInventory()).thenReturn(top);
            when(top.getSize()).thenReturn(TradeGuiLayout.SIZE);
            when(tradeGui.isTradeInventory(top)).thenReturn(true);
            when(tradeGui.getTradeHolder(top)).thenReturn(new TradeGui.TradeHolder(sessionId, playerId));
            when(tradeService.getOpenSession(playerId)).thenReturn(currentSession);
            when(player.getUniqueId()).thenReturn(playerId);
            when(player.getName()).thenReturn("tester");
            when(player.getLocation()).thenReturn(mock(Location.class));
            handler = new TradeGuiEventHandler(tradeGui, goldAmountSettingGui, tradeService, inventoryService, messageService);
        }

        private InventoryClickEvent clickEvent(int rawSlot, int slot, ClickType clickType) {
            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getView()).thenReturn(view);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getRawSlot()).thenReturn(rawSlot);
            when(event.getSlot()).thenReturn(slot);
            when(event.getClick()).thenReturn(clickType);
            return event;
        }
    }

    private static TradeSession tradeSession(UUID sessionId, UUID playerId) {
        return new TradeSession(sessionId, playerId, UUID.randomUUID(), "tester", UUID.randomUUID(),
            UUID.randomUUID(), "partner", Instant.now());
    }
}
