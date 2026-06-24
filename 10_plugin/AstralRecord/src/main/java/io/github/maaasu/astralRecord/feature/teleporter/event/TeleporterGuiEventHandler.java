package io.github.maaasu.astralRecord.feature.teleporter.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryClickGuard;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.teleporter.gui.TeleporterGui;
import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.feature.teleporter.service.TeleporterService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

/**
 * テレポーター GUI のクリック処理を担当します。
 */
public final class TeleporterGuiEventHandler extends AbstractEventHandler {
    private final TeleporterGui gui;
    private final TeleporterService teleporterService;
    private final InventoryService inventoryService;

    public TeleporterGuiEventHandler(
            @NotNull TeleporterGui gui,
            @NotNull TeleporterService teleporterService,
            @NotNull InventoryService inventoryService
    ) {
        this.gui = gui;
        this.teleporterService = teleporterService;
        this.inventoryService = inventoryService;
    }

    /**
     * GUI を開き、共通ホットバーショートカットモードを有効にします。
     *
     * @param player 表示対象
     * @param astPlayer AstralRecord プレイヤー
     * @param source 起点ウェイストーン
     * @param pageIndex 表示ページ
     */
    public void open(@NotNull Player player, @NotNull AstPlayer astPlayer, @NotNull WaystoneDefinition source, int pageIndex) {
        inventoryService.setHotbarShortcutMode(astPlayer, true);
        gui.open(player, astPlayer, source, pageIndex);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        runSafely(() -> {
            Inventory topInventory = event.getView().getTopInventory();
            if (!gui.isInventory(topInventory)) {
                return;
            }
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (handlePlayerInventoryClick(event, player)) {
                return;
            }
            handleTopClick(player, topInventory, event.getRawSlot());
        }, LogId.E_5951, event.getWhoClicked().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        runSafely(() -> {
            if (!gui.isInventory(event.getView().getTopInventory())) {
                return;
            }
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                GuiSound.DENY.play(player);
            }
        }, LogId.E_5951, event.getWhoClicked().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(@NotNull InventoryCloseEvent event) {
        runSafely(() -> {
            if (!gui.isInventory(event.getInventory()) || !(event.getPlayer() instanceof Player player)) {
                return;
            }
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null) {
                inventoryService.setHotbarShortcutMode(astPlayer, false);
            }
        }, LogId.E_5951, event.getPlayer().getName());
    }

    private void handleTopClick(@NotNull Player player, @NotNull Inventory inventory, int rawSlot) {
        TeleporterGui.Holder holder = gui.holder(inventory);
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (holder == null || astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }

        WaystoneDefinition source = teleporterService.getById(holder.sourceWaystoneId());
        if (source == null) {
            GuiSound.DENY.play(player);
            return;
        }
        int itemCount = teleporterService.listGuiEntries(astPlayer, source).size();
        if (rawSlot == TeleporterGui.CLOSE_SLOT) {
            GuiSound.SELECT.play(player);
            player.closeInventory();
            return;
        }
        if (rawSlot == TeleporterGui.PREVIOUS_SLOT && gui.hasPreviousPage(holder.pageIndex())) {
            GuiSound.SELECT.play(player);
            open(player, astPlayer, source, holder.pageIndex() - 1);
            return;
        }
        if (rawSlot == TeleporterGui.NEXT_SLOT && gui.hasNextPage(holder.pageIndex(), itemCount)) {
            GuiSound.SELECT.play(player);
            open(player, astPlayer, source, holder.pageIndex() + 1);
            return;
        }
        if (rawSlot < 0 || rawSlot >= TeleporterGui.CONTENT_SLOT_COUNT || rawSlot >= holder.visibleWaystoneIds().size()) {
            GuiSound.DENY.play(player);
            return;
        }

        WaystoneDefinition target = teleporterService.getById(holder.visibleWaystoneIds().get(rawSlot));
        if (target == null) {
            GuiSound.DENY.play(player);
            return;
        }
        if (!teleporterService.isUnlocked(astPlayer, target)) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5962);
            GuiSound.DENY.play(player);
            return;
        }
        GuiSound.SELECT.play(player);
        teleporterService.teleportToWaystone(player, astPlayer, source, target);
    }

    private boolean handlePlayerInventoryClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            return false;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return true;
        }
        int slot = event.getSlot();
        if (slot >= 0 && slot <= 8
                && inventoryService.getClickGuard().tryAcquire(astPlayer.getAccount().getUuid(), InventoryClickGuard.ClickAction.HOTBAR_SHORTCUT)) {
            boolean handled = inventoryService.handleHotbarShortcutClick(astPlayer, slot);
            if (handled) {
                GuiSound.SELECT.play(player);
            } else {
                GuiSound.DENY.play(player);
            }
            return true;
        }
        GuiSound.DENY.play(player);
        return true;
    }
}
