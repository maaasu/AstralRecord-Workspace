package io.github.maaasu.astralRecord.feature.loginbonus.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.loginbonus.service.LoginBonusService;
import io.github.maaasu.astralRecord.feature.loginbonus.view.LoginBonusGui;
import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
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
        }, LogId.E_5601, event.getWhoClicked().getName(), "login_bonus_click");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        runSafely(() -> {
            if (gui.isLoginBonusInventory(event.getView().getTopInventory())) {
                event.setCancelled(true);
            }
        }, LogId.E_5601, event.getWhoClicked().getName(), "login_bonus_drag");
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
            GuiSound.PAGE.play(player);
            MenuOpenEventHandler.suppressNextCloseSound(player);
            loginBonusService.open(player, displayMonth.minusMonths(1));
            return;
        }
        if (event.getRawSlot() == LoginBonusGui.NEXT_MONTH_SLOT) {
            GuiSound.PAGE.play(player);
            MenuOpenEventHandler.suppressNextCloseSound(player);
            loginBonusService.open(player, displayMonth.plusMonths(1));
            return;
        }
        LocalDate clickedDate = gui.resolveDate(displayMonth, event.getRawSlot());
        if (clickedDate == null) {
            GuiSound.DENY.play(player);
            return;
        }
        loginBonusService.claim(player, clickedDate, success -> {
            if (!player.isOnline()) {
                return;
            }
            if (success) {
                GuiSound.LOGIN_BONUS_REWARD.play(player);
                MenuOpenEventHandler.suppressNextCloseSound(player);
                loginBonusService.open(player, displayMonth);
                return;
            }
            GuiSound.DENY.play(player);
        });
    }

    private boolean handlePlayerInventoryClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        return HotbarShortcutClickSupport.handle(event, player, loginBonusService.getInventoryService());
    }
}
