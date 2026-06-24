package io.github.maaasu.astralRecord.feature.trade.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryClickGuard;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeCancelConfirmGui;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeGui;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeGuiLayout;
import io.github.maaasu.astralRecord.feature.trade.service.TradeService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.gold.GoldAmountSettingGui;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

public final class TradeGuiEventHandler extends AbstractEventHandler {
    private final AstralRecord plugin;
    private final TradeGui tradeGui;
    private final TradeCancelConfirmGui cancelConfirmGui;
    private final GoldAmountSettingGui goldAmountSettingGui;
    private final TradeService tradeService;
    private final InventoryService inventoryService;
    private final PlayerMessageService messageService;

    public TradeGuiEventHandler(
        @NotNull AstralRecord plugin,
        @NotNull TradeGui tradeGui,
        @NotNull TradeCancelConfirmGui cancelConfirmGui,
        @NotNull GoldAmountSettingGui goldAmountSettingGui,
        @NotNull TradeService tradeService,
        @NotNull InventoryService inventoryService,
        @NotNull PlayerMessageService messageService
    ) {
        this.plugin = plugin;
        this.tradeGui = tradeGui;
        this.cancelConfirmGui = cancelConfirmGui;
        this.goldAmountSettingGui = goldAmountSettingGui;
        this.tradeService = tradeService;
        this.inventoryService = inventoryService;
        this.messageService = messageService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        runSafely(() -> {
            Inventory top = event.getView().getTopInventory();
            if (tradeGui.isTradeInventory(top)) {
                if (event.getWhoClicked() instanceof Player player
                    && !AccountModeGuard.isGameplayPlayer(player)) {
                    event.setCancelled(true);
                    player.closeInventory();
                    return;
                }
                handleTradeClick(event);
                return;
            }
            if (cancelConfirmGui.isCancelInventory(top)) {
                handleCancelConfirmClick(event);
                return;
            }
            if (goldAmountSettingGui.isGoldAmountInventory(top)) {
                if (event.getWhoClicked() instanceof Player player
                    && !AccountModeGuard.isGameplayPlayer(player)) {
                    event.setCancelled(true);
                    player.closeInventory();
                    return;
                }
                handleGoldAmountClick(event);
            }
        }, LogId.E_6200, event.getWhoClicked().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        runSafely(() -> {
            Inventory top = event.getView().getTopInventory();
            if (!tradeGui.isTradeInventory(top)
                && !cancelConfirmGui.isCancelInventory(top)
                && !goldAmountSettingGui.isGoldAmountInventory(top)) {
                return;
            }
            if (event.getWhoClicked() instanceof Player player
                && !AccountModeGuard.isGameplayPlayer(player)) {
                event.setCancelled(true);
                player.closeInventory();
                return;
            }
            if (cancelConfirmGui.isCancelInventory(top) || goldAmountSettingGui.isGoldAmountInventory(top)) {
                event.setCancelled(true);
                return;
            }
            boolean touchesTop = event.getRawSlots().stream().anyMatch(slot -> slot < top.getSize());
            if (!touchesTop) {
                return;
            }
            boolean allOwnSlots = event.getRawSlots().stream()
                .filter(slot -> slot < top.getSize())
                .allMatch(TradeGuiLayout.OWN_SLOTS::contains);
            if (!allOwnSlots || !tradeService.isTradeable(event.getOldCursor())) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player && !tradeService.isTradeable(event.getOldCursor())) {
                    messageService.send(player, PlayerMsgId.P_6204);
                }
                return;
            }
            if (event.getWhoClicked() instanceof Player player) {
                scheduleCaptureAndRefresh(player);
            }
        }, LogId.E_6200, event.getWhoClicked().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory inventory = event.getInventory();
        boolean tradeInventory = tradeGui.isTradeInventory(inventory);
        boolean cancelInventory = cancelConfirmGui.isCancelInventory(inventory);
        boolean goldAmountInventory = goldAmountSettingGui.isGoldAmountInventory(inventory);
        if (!tradeInventory && !cancelInventory && !goldAmountInventory) {
            return;
        }
        if (tradeService.consumeSuppressedClose(player.getUniqueId())) {
            return;
        }
        if (tradeInventory) {
            tradeService.captureInventory(player, inventory);
            Bukkit.getScheduler().runTask(plugin, () -> tradeService.openCancelConfirmAfterClose(player));
            return;
        }
        if (goldAmountInventory) {
            Bukkit.getScheduler().runTask(plugin, () -> tradeService.reopenTradeAfterClose(player));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> tradeService.cancelTrade(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        tradeService.cancelTrade(event.getPlayer());
    }

    private void handleTradeClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        if (handleHotbarShortcutClick(event, player)) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot == TradeGuiLayout.GOLD_SLOT) {
            event.setCancelled(true);
            tradeService.openGoldAmountSetting(player);
            GuiSound.SELECT.play(player);
            return;
        }
        if (rawSlot == TradeGuiLayout.READY_SLOT) {
            event.setCancelled(true);
            tradeService.toggleReady(player);
            GuiSound.SELECT.play(player);
            return;
        }
        if (rawSlot >= 0 && rawSlot < event.getView().getTopInventory().getSize()) {
            if (!TradeGuiLayout.OWN_SLOTS.contains(rawSlot)) {
                event.setCancelled(true);
                GuiSound.DENY.play(player);
                return;
            }
            if (!tradeService.isTradeable(event.getCursor()) || !tradeService.isTradeable(event.getCurrentItem())) {
                event.setCancelled(true);
                messageService.send(player, PlayerMsgId.P_6204);
                GuiSound.DENY.play(player);
                return;
            }
            event.setCancelled(false);
            scheduleCaptureAndRefresh(player);
            return;
        }
        if (event.isShiftClick() && !tradeService.isTradeable(event.getCurrentItem())) {
            event.setCancelled(true);
            messageService.send(player, PlayerMsgId.P_6204);
            GuiSound.DENY.play(player);
            return;
        }
        if (event.isShiftClick()) {
            scheduleCaptureAndRefresh(player);
        }
    }

    private void handleCancelConfirmClick(@NotNull InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getRawSlot() == TradeCancelConfirmGui.CANCEL_SLOT) {
            tradeService.cancelTrade(player);
            GuiSound.CLOSE.play(player);
            return;
        }
        if (event.getRawSlot() == TradeCancelConfirmGui.BACK_SLOT) {
            tradeService.reopenTrade(player);
            GuiSound.SELECT.play(player);
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void handleGoldAmountClick(@NotNull InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        GoldAmountSettingGui.GoldAmountHolder holder = goldAmountSettingGui.getHolder(event.getView().getTopInventory());
        if (holder == null || !TradeService.GOLD_AMOUNT_SOURCE_KEY.equals(holder.sourceKey())) {
            GuiSound.DENY.play(player);
            return;
        }
        long amount = switch (event.getRawSlot()) {
            case GoldAmountSettingGui.CLEAR_SLOT -> 0L;
            case GoldAmountSettingGui.MINUS_1000_SLOT -> goldAmountSettingGui.applyDelta(holder, -1000L);
            case GoldAmountSettingGui.MINUS_100_SLOT -> goldAmountSettingGui.applyDelta(holder, -100L);
            case GoldAmountSettingGui.PLUS_100_SLOT -> goldAmountSettingGui.applyDelta(holder, 100L);
            case GoldAmountSettingGui.PLUS_1000_SLOT -> goldAmountSettingGui.applyDelta(holder, 1000L);
            case GoldAmountSettingGui.MAX_SLOT -> holder.maxAmount();
            default -> holder.amount();
        };
        if (event.getRawSlot() == GoldAmountSettingGui.BACK_SLOT) {
            tradeService.reopenTrade(player);
            GuiSound.SELECT.play(player);
            return;
        }
        if (event.getRawSlot() == GoldAmountSettingGui.CONFIRM_SLOT) {
            tradeService.applyGoldAmount(player, holder.contextId(), holder.amount());
            GuiSound.SELECT.play(player);
            return;
        }
        if (amount != holder.amount()) {
            holder.setAmount(amount);
            goldAmountSettingGui.rerender(event.getView().getTopInventory(), amount, holder.maxAmount());
            GuiSound.SELECT.play(player);
            return;
        }
        GuiSound.DENY.play(player);
    }

    private boolean handleHotbarShortcutClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            return false;
        }
        int slot = event.getSlot();
        if (slot < 0 || slot > 8) {
            return false;
        }
        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !inventoryService.isHotbarShortcutMode(astPlayer)) {
            return false;
        }
        event.setCancelled(true);
        if (!inventoryService.getClickGuard().tryAcquire(
            astPlayer.getAccount().getUuid(), InventoryClickGuard.ClickAction.HOTBAR_SHORTCUT)) {
            return true;
        }
        if (slot == 4 || event.getClick() == ClickType.DROP) {
            tradeService.openCancelConfirm(player);
            GuiSound.CLOSE.play(player);
        } else {
            GuiSound.DENY.play(player);
        }
        return true;
    }

    private void scheduleCaptureAndRefresh(@NotNull Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            var session = tradeService.getOpenSession(player.getUniqueId());
            if (session == null) {
                return;
            }
            tradeService.captureOpenInventory(player);
            tradeService.reopenTrade(player);
            Player partner = Bukkit.getPlayer(session.getPartnerUuid(player.getUniqueId()));
            if (partner != null && partner.isOnline()) {
                tradeService.reopenTrade(partner);
            }
        });
    }
}
