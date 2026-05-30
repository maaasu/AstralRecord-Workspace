package io.github.maaasu.astralRecord.feature.loginbonus.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.loginbonus.view.LoginBonusGui;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.jetbrains.annotations.NotNull;

/**
 * ログインボーナス GUI の操作を処理します。
 */
public final class LoginBonusGuiEventHandler extends AbstractEventHandler {
    private final LoginBonusGui gui;

    /**
     * ログインボーナス GUI イベントハンドラを構築します。
     *
     * @param gui 対象 GUI
     */
    public LoginBonusGuiEventHandler(@NotNull LoginBonusGui gui) {
        this.gui = gui;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        runSafely(() -> {
            if (!gui.isLoginBonusInventory(event.getView().getTopInventory())) {
                return;
            }
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (event.getRawSlot() == LoginBonusGui.CLOSE_SLOT) {
                GuiSound.CLOSE.play(player);
                player.closeInventory();
                return;
            }
            GuiSound.DENY.play(player);
        }, LogId.E_5070, "login_bonus_click: " + event.getWhoClicked().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        runSafely(() -> {
            if (gui.isLoginBonusInventory(event.getView().getTopInventory())) {
                event.setCancelled(true);
            }
        }, LogId.E_5070, "login_bonus_drag: " + event.getWhoClicked().getName());
    }
}
