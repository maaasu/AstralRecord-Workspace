package io.github.maaasu.astralRecord.feature.loginbonus.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryClickGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.loginbonus.service.LoginBonusService;
import io.github.maaasu.astralRecord.feature.loginbonus.view.LoginBonusGui;
import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * ログイン報酬 GUI の操作を処理します。
 */
public final class LoginBonusGuiEventHandler extends AbstractEventHandler {
    private final LoginBonusService loginBonusService;
    private final LoginBonusGui gui;

    /**
     * ログイン報酬 GUI イベントハンドラを構築します。
     *
     * @param loginBonusService ログイン報酬サービス
     */
    public LoginBonusGuiEventHandler(@NotNull LoginBonusService loginBonusService) {
        this.loginBonusService = loginBonusService;
        this.gui = loginBonusService.getGui();
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
            handleClick(event, player);
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

    private void handleClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        if (handlePlayerInventoryClick(event, player)) {
            return;
        }
        YearMonth displayMonth = gui.getDisplayMonth(event.getView().getTopInventory());
        if (displayMonth == null) {
            GuiSound.DENY.play(player);
            return;
        }
        if (event.getRawSlot() == LoginBonusGui.PREVIOUS_MONTH_SLOT) {
            GuiSound.SELECT.play(player);
            MenuOpenEventHandler.suppressNextCloseSound(player);
            loginBonusService.open(player, displayMonth.minusMonths(1));
            return;
        }
        if (event.getRawSlot() == LoginBonusGui.NEXT_MONTH_SLOT) {
            GuiSound.SELECT.play(player);
            MenuOpenEventHandler.suppressNextCloseSound(player);
            loginBonusService.open(player, displayMonth.plusMonths(1));
            return;
        }
        LocalDate clickedDate = gui.resolveDate(displayMonth, event.getRawSlot());
        if (clickedDate == null) {
            GuiSound.DENY.play(player);
            return;
        }
        if (loginBonusService.claim(player, clickedDate)) {
            GuiSound.SELECT.play(player);
            MenuOpenEventHandler.suppressNextCloseSound(player);
            loginBonusService.open(player, displayMonth);
        } else {
            GuiSound.DENY.play(player);
        }
    }

    private boolean handlePlayerInventoryClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            return false;
        }
        int slot = event.getSlot();
        if (slot < 0 || slot > 8) {
            GuiSound.DENY.play(player);
            return true;
        }
        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !loginBonusService.getInventoryService().isHotbarShortcutMode(astPlayer)) {
            GuiSound.DENY.play(player);
            return true;
        }
        if (!loginBonusService.getInventoryService().getClickGuard().tryAcquire(
            astPlayer.getAccount().getUuid(),
            InventoryClickGuard.ClickAction.HOTBAR_SHORTCUT
        )) {
            return true;
        }
        boolean handled = loginBonusService.getInventoryService().handleHotbarShortcutClick(astPlayer, slot);
        if (handled) {
            if (slot == 4) {
                GuiSound.CLOSE.play(player);
            } else {
                GuiSound.SELECT.play(player);
            }
        } else {
            GuiSound.DENY.play(player);
        }
        return true;
    }
}
