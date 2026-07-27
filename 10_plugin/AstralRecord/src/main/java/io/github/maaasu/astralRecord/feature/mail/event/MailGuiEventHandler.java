package io.github.maaasu.astralRecord.feature.mail.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.mail.gui.MailGuiView;
import io.github.maaasu.astralRecord.feature.mail.model.MailEntry;
import io.github.maaasu.astralRecord.feature.mail.model.MailFilter;
import io.github.maaasu.astralRecord.feature.mail.service.MailService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * メール GUI のクリック操作を処理します。
 */
public final class MailGuiEventHandler extends AbstractEventHandler {
    private final MailGuiView mailGuiView;
    private final MailService mailService;
    private final InventoryService inventoryService;
    private final Map<UUID, PendingOpenRequest> openRequests = new ConcurrentHashMap<>();

    public MailGuiEventHandler(
        @NotNull MailGuiView mailGuiView,
        @NotNull MailService mailService,
        @NotNull InventoryService inventoryService
    ) {
        this.mailGuiView = mailGuiView;
        this.mailService = mailService;
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
        UUID playerId = player.getUniqueId();
        UUID userId = astPlayer.getUser().getUuid();
        UUID accountId = astPlayer.getAccount().getUuid();
        PendingOpenRequest request = new PendingOpenRequest(
            UUID.randomUUID(),
            player.getOpenInventory().getTopInventory()
        );
        openRequests.put(playerId, request);
        mailService.listAsync(
            userId,
            filter,
            mails -> {
                if (!openRequests.remove(playerId, request)) {
                    return;
                }
                Player online = org.bukkit.Bukkit.getPlayer(playerId);
                var current = online == null ? null : AstPlayerCache.get(online);
                if (online == null || !online.isOnline() || current == null
                    || !current.getUser().getUuid().equals(userId)
                    || !current.getAccount().getUuid().equals(accountId)
                    || online.getOpenInventory().getTopInventory() != request.expectedInventory()) {
                    return;
                }
                mailGuiView.open(online, mails, filter, pageIndex);
            },
            () -> {
                if (openRequests.remove(playerId, request) && player.isOnline()
                    && player.getOpenInventory().getTopInventory() == request.expectedInventory()) {
                    GuiSound.DENY.play(player);
                }
            }
        );
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
        }, LogId.E_5601, event.getWhoClicked().getName(), "mail_gui_click");
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
        }, LogId.E_5601, event.getWhoClicked().getName(), "mail_gui_drag");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(@NotNull InventoryCloseEvent event) {
        runSafely(() -> {
            if (!(event.getPlayer() instanceof Player player)) {
                return;
            }
            UUID playerId = player.getUniqueId();
            PendingOpenRequest pending = openRequests.get(playerId);
            if (pending != null && pending.expectedInventory() == event.getInventory()) {
                openRequests.remove(playerId, pending);
            }
        }, LogId.E_5601, event.getPlayer().getName(), "mail_gui_close");
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
            AstralRecord.getInstance().getGuiNavigationService().openPrevious(player);
            return;
        }
        if (event.getRawSlot() == MailGuiView.FILTER_SLOT) {
            GuiSound.SELECT.play(player);
            MenuOpenEventHandler.suppressNextCloseSound(player);
            open(player, filter.next(), 0);
            return;
        }

        List<MailEntry> mails = mailGuiView.getMails(topInventory);
        if (event.getRawSlot() == MailGuiView.PREVIOUS_SLOT && mailGuiView.hasPreviousPage(pageIndex)) {
            GuiSound.SELECT.play(player);
            MenuOpenEventHandler.suppressNextCloseSound(player);
            mailGuiView.open(player, mails, filter, pageIndex - 1);
            return;
        }
        if (event.getRawSlot() == MailGuiView.NEXT_SLOT && mailGuiView.hasNextPage(mails, pageIndex)) {
            GuiSound.SELECT.play(player);
            MenuOpenEventHandler.suppressNextCloseSound(player);
            mailGuiView.open(player, mails, filter, pageIndex + 1);
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
            case DROP, CONTROL_DROP -> {
                mailService.delete(
                    astPlayer,
                    mailId,
                    success -> finishMutation(player, topInventory, filter, pageIndex, success)
                );
            }
            case LEFT, SHIFT_LEFT -> {
                mailService.readAndReceive(astPlayer, mail, success ->
                    finishMutation(player, topInventory, filter, pageIndex, success));
            }
            default -> GuiSound.DENY.play(player);
        }
    }

    private boolean handleHotbarShortcutClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        return HotbarShortcutClickSupport.handle(event, player, inventoryService);
    }

    private void finishMutation(
        @NotNull Player player,
        @NotNull Inventory expectedInventory,
        @NotNull MailFilter filter,
        int pageIndex,
        boolean success
    ) {
        if (!player.isOnline()
            || player.getOpenInventory().getTopInventory() != expectedInventory
            || !mailGuiView.isInventory(expectedInventory)) {
            return;
        }
        if (!success) {
            GuiSound.DENY.play(player);
            return;
        }
        GuiSound.SELECT.play(player);
        MenuOpenEventHandler.suppressNextCloseSound(player);
        open(player, filter, pageIndex);
    }

    private record PendingOpenRequest(
        @NotNull UUID requestId,
        @NotNull Inventory expectedInventory
    ) {
    }
}
