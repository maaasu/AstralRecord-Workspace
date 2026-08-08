package io.github.maaasu.astralRecord.shared.gui.navigation;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionEndEvent;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.jetbrains.annotations.NotNull;

/**
 * GUI の open / close / 戻るクリックを共通履歴サービスへ接続します。
 */
public final class GuiNavigationEventHandler extends AbstractEventHandler {
    private final GuiNavigationService navigationService;

    /**
     * GUI ナビゲーションイベントハンドラを生成します。
     *
     * @param navigationService GUI 履歴サービス
     */
    public GuiNavigationEventHandler(@NotNull GuiNavigationService navigationService) {
        this.navigationService = navigationService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(@NotNull InventoryOpenEvent event) {
        runSafely(() -> {
            if (event.getPlayer() instanceof Player player) {
                navigationService.registerOpen(player, event.getInventory());
            }
        }, LogId.E_5601, event.getPlayer().getName(), "gui_navigation_open");
    }

    /**
     * 終了が確定した GUI セッションだけの戻る履歴を破棄します。
     *
     * @param event 共有基盤が発行した GUI セッション終了イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuiSessionEnd(@NotNull GuiSessionEndEvent event) {
        runSafely(() -> {
            navigationService.completeSessionClose(event.getPlayer(), event.getInventory());
        }, LogId.E_5601, event.getPlayer().getName(), "gui_navigation_close");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        runSafely(() -> {
            if (!navigationService.isBackClick(event.getView().getTopInventory(), event.getRawSlot())) {
                return;
            }
            if (!(event.getWhoClicked() instanceof Player player)) {
                event.setCancelled(true);
                return;
            }
            if (navigationService.isCloseNavigation(player, event.getView().getTopInventory())) {
                event.setCancelled(true);
                player.closeInventory();
                return;
            }
            if (!navigationService.isDirectBackClick(event.getView().getTopInventory(), event.getRawSlot())) {
                return;
            }
            event.setCancelled(true);
            if (navigationService.openPrevious(player)) {
                GuiSound.SELECT.play(player);
            } else {
                GuiSound.DENY.play(player);
            }
        }, LogId.E_5601, event.getWhoClicked().getName(), "gui_navigation_back");
    }
}
