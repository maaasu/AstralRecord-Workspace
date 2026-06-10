package io.github.maaasu.astralRecord.shared.gui.debug.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.shared.gui.debug.PagingDebugGui;
import io.github.maaasu.astralRecord.shared.gui.paging.PagedGuiView;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.jetbrains.annotations.NotNull;

/**
 * ページング確認用のダミー GUI 操作を処理します。
 */
public final class PagingDebugGuiEventHandler extends AbstractEventHandler {
    private final PagingDebugGui pagingDebugGui;
    private final MenuView menuView;

    /**
     * ダミー GUI イベントハンドラを生成します。
     *
     * @param pagingDebugGui ページング確認 GUI
     * @param menuView 戻る操作で開くメニュー
     */
    public PagingDebugGuiEventHandler(
        @NotNull PagingDebugGui pagingDebugGui,
        @NotNull MenuView menuView
    ) {
        this.pagingDebugGui = pagingDebugGui;
        this.menuView = menuView;
    }

    /**
     * ダミー GUI のクリックを処理します。
     *
     * @param event インベントリクリックイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        runSafely(() -> {
            if (!pagingDebugGui.isDebugInventory(event.getView().getTopInventory())) {
                return;
            }
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            handleClick(player, event.getRawSlot(), pagingDebugGui.getPageIndex(event.getView().getTopInventory()));
        }, LogId.E_5600, event.getWhoClicked().getName());
    }

    /**
     * ダミー GUI 内へのドラッグを抑止します。
     *
     * @param event インベントリドラッグイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        runSafely(() -> {
            if (!pagingDebugGui.isDebugInventory(event.getView().getTopInventory())) {
                return;
            }
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                GuiSound.DENY.play(player);
            }
        }, LogId.E_5600, event.getWhoClicked().getName());
    }

    private void handleClick(@NotNull Player player, int rawSlot, int pageIndex) {
        if (rawSlot == PagedGuiView.CLOSE_SLOT) {
            GuiSound.CLOSE.play(player);
            player.closeInventory();
            return;
        }
        if (rawSlot == PagedGuiView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            menuView.open(player);
            return;
        }
        if (rawSlot == PagedGuiView.PREVIOUS_SLOT && pagingDebugGui.hasPreviousPage(pageIndex)) {
            GuiSound.SELECT.play(player);
            pagingDebugGui.open(player, pageIndex - 1);
            return;
        }
        if (rawSlot == PagedGuiView.NEXT_SLOT && pagingDebugGui.hasNextPage(pageIndex)) {
            GuiSound.SELECT.play(player);
            pagingDebugGui.open(player, pageIndex + 1);
            return;
        }
        GuiSound.DENY.play(player);
    }
}
