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
import io.github.maaasu.astralRecord.feature.trade.model.TradeSession;
import io.github.maaasu.astralRecord.feature.trade.service.TradeService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.gold.GoldAmountSettingGui;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

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

    /**
     * トレード関連 GUI のクリックを処理します。
     *
     * @param event Bukkit の inventory click event
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
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
                if (event.getWhoClicked() instanceof Player player && !isCurrentTradeView(top, player)) {
                    event.setCancelled(true);
                    GuiSound.DENY.play(player);
                    return;
                }
                handleTradeClick(event);
                return;
            }
            if (cancelConfirmGui.isCancelInventory(top)) {
                if (event.getWhoClicked() instanceof Player player && !isCurrentCancelConfirmView(top, player)) {
                    event.setCancelled(true);
                    GuiSound.DENY.play(player);
                    return;
                }
                handleCancelConfirmClick(event);
                return;
            }
            if (isTradeGoldAmountInventory(top)) {
                if (event.getWhoClicked() instanceof Player player
                    && !AccountModeGuard.isGameplayPlayer(player)) {
                    event.setCancelled(true);
                    player.closeInventory();
                    return;
                }
                if (event.getWhoClicked() instanceof Player player && !isCurrentGoldAmountView(top, player)) {
                    event.setCancelled(true);
                    GuiSound.DENY.play(player);
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
                && !isTradeGoldAmountInventory(top)) {
                return;
            }
            if (event.getWhoClicked() instanceof Player player
                && !AccountModeGuard.isGameplayPlayer(player)) {
                event.setCancelled(true);
                player.closeInventory();
                return;
            }
            if (cancelConfirmGui.isCancelInventory(top) || isTradeGoldAmountInventory(top)) {
                event.setCancelled(true);
                return;
            }
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                GuiSound.DENY.play(player);
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
        boolean goldAmountInventory = isTradeGoldAmountInventory(inventory);
        if (!tradeInventory && !cancelInventory && !goldAmountInventory) {
            return;
        }
        if (tradeService.consumeSuppressedClose(player.getUniqueId())) {
            return;
        }
        if (tradeInventory) {
            if (!isCurrentTradeView(inventory, player)) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> tradeService.openCancelConfirmAfterClose(player));
            return;
        }
        if (goldAmountInventory) {
            if (!isCurrentGoldAmountView(inventory, player)) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> tradeService.reopenTradeAfterClose(player));
            return;
        }
        if (!isCurrentCancelConfirmView(inventory, player)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> tradeService.cancelTrade(player));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        tradeService.cancelTrade(event.getPlayer());
    }

    /**
     * 許可対象外ワールドへの移動時に、開いているトレードを中止します。
     *
     * @param event ワールド移動イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (tradeService.getOpenSession(player.getUniqueId()) != null
            && !tradeService.isTradeAllowedWorld(player)) {
            tradeService.cancelTrade(player);
        }
    }

    /**
     * トレード GUI の操作を処理し、プレイヤー inventory のスクロールは共通処理へ委譲します。
     *
     * @param event トレード GUI 上の click event
     */
    private void handleTradeClick(@NotNull InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (HotbarShortcutClickSupport.handleInventoryControlClick(event, player, inventoryService)) {
            return;
        }
        if (handleHotbarShortcutClick(event, player)) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot == TradeGuiLayout.GOLD_SLOT) {
            tradeService.openGoldAmountSetting(player);
            GuiSound.SELECT.play(player);
            return;
        }
        if (rawSlot == TradeGuiLayout.READY_SLOT) {
            tradeService.toggleReady(player);
            GuiSound.SELECT.play(player);
            return;
        }
        if (rawSlot >= 0 && rawSlot < event.getView().getTopInventory().getSize()) {
            if (!TradeGuiLayout.OWN_SLOTS.contains(rawSlot)) {
                GuiSound.DENY.play(player);
                return;
            }
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                GuiSound.DENY.play(player);
                return;
            }
            int offerIndex = TradeGuiLayout.OWN_SLOT_LIST.indexOf(rawSlot);
            if (tradeService.withdrawOfferedItem(player, offerIndex, event.getClick())) {
                GuiSound.SELECT.play(player);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            GuiSound.DENY.play(player);
            return;
        }
        ItemStack current = event.getCurrentItem();
        if (!tradeService.isTradeable(current)) {
            if (current != null && !current.getType().isAir()) {
                messageService.send(player, PlayerMsgId.P_6204);
            }
            GuiSound.DENY.play(player);
            return;
        }
        if (tradeService.offerOwnedItem(player, event.getSlot(), event.getClick(), current)) {
            GuiSound.SELECT.play(player);
        } else {
            GuiSound.DENY.play(player);
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
        int rawSlot = event.getRawSlot();
        if (rawSlot == GoldAmountSettingGui.BACK_SLOT) {
            tradeService.reopenTrade(player);
            GuiSound.SELECT.play(player);
            return;
        }
        if (rawSlot == GoldAmountSettingGui.CONFIRM_SLOT) {
            tradeService.applyGoldAmount(player, holder.contextId(), holder.amount());
            GuiSound.SELECT.play(player);
            return;
        }
        if (rawSlot == GoldAmountSettingGui.STEP_DOWN_SLOT
            || rawSlot == GoldAmountSettingGui.STEP_UP_SLOT) {
            long previousStep = holder.step();
            int digitChange = event.isShiftClick() ? 3 : 1;
            goldAmountSettingGui.shiftStep(
                holder,
                rawSlot == GoldAmountSettingGui.STEP_DOWN_SLOT ? -digitChange : digitChange
            );
            goldAmountSettingGui.rerender(event.getView().getTopInventory(), holder);
            if (previousStep == holder.step()) {
                GuiSound.DENY.play(player);
            } else {
                GuiSound.SELECT.play(player);
            }
            return;
        }
        int multiplier = resolveGoldAdjustmentMultiplier(event);
        long amount = switch (event.getRawSlot()) {
            case GoldAmountSettingGui.CLEAR_SLOT -> 0L;
            case GoldAmountSettingGui.MINUS_SLOT -> goldAmountSettingGui.applyStepDelta(holder, -1, multiplier);
            case GoldAmountSettingGui.HALF_SLOT -> holder.amount() / 2L;
            case GoldAmountSettingGui.DOUBLE_SLOT -> goldAmountSettingGui.applyDelta(holder, holder.amount());
            case GoldAmountSettingGui.PLUS_SLOT -> goldAmountSettingGui.applyStepDelta(holder, 1, multiplier);
            case GoldAmountSettingGui.MAX_SLOT -> holder.maxAmount();
            default -> holder.amount();
        };
        if (amount != holder.amount()) {
            holder.setAmount(amount);
            goldAmountSettingGui.rerender(event.getView().getTopInventory(), holder);
            GuiSound.SELECT.play(player);
            return;
        }
        GuiSound.DENY.play(player);
    }

    /**
     * Gold 増減ボタンのクリック種別から調整倍率を解決します。
     *
     * @param event 金額設定 GUI のクリックイベント
     * @return 通常1倍、右クリック5倍、Shiftクリック10倍
     */
    private int resolveGoldAdjustmentMultiplier(@NotNull InventoryClickEvent event) {
        if (event.isShiftClick()) {
            return 10;
        }
        return event.isRightClick() ? 5 : 1;
    }

    private boolean isCurrentTradeView(@NotNull Inventory inventory, @NotNull Player player) {
        TradeGui.TradeHolder holder = tradeGui.getTradeHolder(inventory);
        return holder != null
            && isCurrentSessionView(player, holder.viewerUuid(), holder.sessionId());
    }

    private boolean isCurrentCancelConfirmView(@NotNull Inventory inventory, @NotNull Player player) {
        TradeCancelConfirmGui.CancelHolder holder = cancelConfirmGui.getCancelHolder(inventory);
        return holder != null
            && isCurrentSessionView(player, holder.viewerUuid(), holder.sessionId());
    }

    private boolean isCurrentGoldAmountView(@NotNull Inventory inventory, @NotNull Player player) {
        GoldAmountSettingGui.GoldAmountHolder holder = goldAmountSettingGui.getHolder(inventory);
        return holder != null
            && TradeService.GOLD_AMOUNT_SOURCE_KEY.equals(holder.sourceKey())
            && isCurrentSessionView(player, holder.viewerUuid(), holder.contextId());
    }

    private boolean isTradeGoldAmountInventory(@Nullable Inventory inventory) {
        GoldAmountSettingGui.GoldAmountHolder holder = goldAmountSettingGui.getHolder(inventory);
        return holder != null && TradeService.GOLD_AMOUNT_SOURCE_KEY.equals(holder.sourceKey());
    }

    private boolean isCurrentSessionView(
        @NotNull Player player,
        @NotNull UUID viewerUuid,
        @NotNull UUID sessionId
    ) {
        if (!viewerUuid.equals(player.getUniqueId())) {
            return false;
        }
        TradeSession session = tradeService.getOpenSession(player.getUniqueId());
        return session != null && session.getSessionId().equals(sessionId);
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

}
