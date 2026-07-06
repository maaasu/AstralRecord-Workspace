package io.github.maaasu.astralRecord.feature.mail.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.mail.gui.MailGuiView;
import io.github.maaasu.astralRecord.feature.mail.model.MailEntry;
import io.github.maaasu.astralRecord.feature.mail.model.MailFilter;
import io.github.maaasu.astralRecord.feature.mail.service.MailService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * メール GUI のクリック操作を処理します。
 */
public final class MailGuiEventHandler extends AbstractEventHandler {
    private final MailGuiView mailGuiView;
    private final MailService mailService;
    private final MenuView menuView;
    private final InventoryService inventoryService;

    public MailGuiEventHandler(
        @NotNull MailGuiView mailGuiView,
        @NotNull MailService mailService,
        @NotNull MenuView menuView,
        @NotNull InventoryService inventoryService
    ) {
        this.mailGuiView = mailGuiView;
        this.mailService = mailService;
        this.menuView = menuView;
        this.inventoryService = inventoryService;
    }

    public void open(@NotNull Player player) {
        open(player, MailFilter.ALL, 0);
    }

    public void open(@NotNull Player player, @NotNull MailFilter filter, int pageIndex) {
        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        List<MailEntry> mails = mailService.list(player.getUniqueId(), filter);
        mailGuiView.open(player, mails, filter, pageIndex);
    }

    public boolean isInventory(org.bukkit.inventory.Inventory inventory) {
        return mailGuiView.isInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        runSafely(() -> {
            if (!mailGuiView.isInventory(event.getView().getTopInventory())) {
                return;
            }
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            handleClick(event, player);
        }, LogId.E_5600, event.getWhoClicked().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        runSafely(() -> {
            if (mailGuiView.isInventory(event.getView().getTopInventory())) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    GuiSound.DENY.play(player);
                }
            }
        }, LogId.E_5600, event.getWhoClicked().getName());
    }

    private void handleClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        var topInventory = event.getView().getTopInventory();
        MailFilter filter = mailGuiView.getFilter(topInventory);
        int pageIndex = mailGuiView.getPageIndex(topInventory);

        if (handleHotbarShortcutClick(event, player)) {
            return;
        }
        if (event.getRawSlot() == MailGuiView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            MenuOpenEventHandler.suppressNextCloseSound(player);
            menuView.open(player);
            return;
        }
        if (event.getRawSlot() == MailGuiView.FILTER_SLOT) {
            GuiSound.SELECT.play(player);
            MenuOpenEventHandler.suppressNextCloseSound(player);
            open(player, filter.next(), 0);
            return;
        }

        List<MailEntry> mails = mailService.list(player.getUniqueId(), filter);
        if (event.getRawSlot() == MailGuiView.PREVIOUS_SLOT && mailGuiView.hasPreviousPage(pageIndex)) {
            GuiSound.SELECT.play(player);
            MenuOpenEventHandler.suppressNextCloseSound(player);
            open(player, filter, pageIndex - 1);
            return;
        }
        if (event.getRawSlot() == MailGuiView.NEXT_SLOT && mailGuiView.hasNextPage(mails, pageIndex)) {
            GuiSound.SELECT.play(player);
            MenuOpenEventHandler.suppressNextCloseSound(player);
            open(player, filter, pageIndex + 1);
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= MailGuiView.CONTENT_SLOT_COUNT) {
            GuiSound.DENY.play(player);
            return;
        }

        String mailId = mailGuiView.getMailId(event.getCurrentItem());
        if (mailId == null || mailId.isBlank()) {
            GuiSound.DENY.play(player);
            return;
        }
        MailEntry mail = mails.stream()
            .filter(candidate -> candidate.id().equals(mailId))
            .findFirst()
            .orElse(null);
        if (mail == null) {
            GuiSound.DENY.play(player);
            return;
        }

        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        switch (event.getClick()) {
            case SHIFT_RIGHT -> {
                if (mailService.delete(astPlayer, mailId)) {
                    GuiSound.SELECT.play(player);
                    MenuOpenEventHandler.suppressNextCloseSound(player);
                    open(player, filter, pageIndex);
                } else {
                    GuiSound.DENY.play(player);
                }
            }
            case LEFT, SHIFT_LEFT -> {
                if (mailService.readAndReceive(astPlayer, mail)) {
                    GuiSound.SELECT.play(player);
                    MenuOpenEventHandler.suppressNextCloseSound(player);
                    open(player, filter, pageIndex);
                } else {
                    GuiSound.DENY.play(player);
                }
            }
            default -> GuiSound.DENY.play(player);
        }
    }

    private boolean handleHotbarShortcutClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        return HotbarShortcutClickSupport.handle(event, player, inventoryService);
    }
}
