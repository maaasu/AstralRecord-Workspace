package io.github.maaasu.astralRecord.feature.sell.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.service.ItemReferenceResolver;
import io.github.maaasu.astralRecord.feature.item.service.ItemTransferSupport;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.service.MenuGuiTransitionService;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.sell.view.SellScreenView;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 売却 GUI の状態管理とイベント処理を担当するサービスです。
 */
public final class SellService {
    private final AstralRecord plugin;
    private final MenuView menuView;
    private final InventoryService inventoryService;
    private final MenuGuiTransitionService menuGuiTransitionService;
    private final ItemReferenceResolver transferItemResolver;
    private final ConcurrentHashMap<UUID, List<ItemStack>> sellItemsByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> sellPageByPlayer = new ConcurrentHashMap<>();
    private final Set<UUID> suppressSellConfirmOnClose = ConcurrentHashMap.newKeySet();
    private final Set<UUID> suppressSellConfirmRestoreOnClose = ConcurrentHashMap.newKeySet();

    /**
     * 売却 GUI サービスを初期化します。
     *
     * @param plugin プラグイン本体
     * @param menuView メニュー GUI 表示
     * @param inventoryService インベントリサービス
     * @param menuGuiTransitionService GUI 切替サービス
     */
    public SellService(
        @NotNull AstralRecord plugin,
        @NotNull MenuView menuView,
        @NotNull InventoryService inventoryService,
        @NotNull MenuGuiTransitionService menuGuiTransitionService
    ) {
        this.plugin = plugin;
        this.menuView = menuView;
        this.inventoryService = inventoryService;
        this.menuGuiTransitionService = menuGuiTransitionService;
        this.transferItemResolver = new ItemReferenceResolver(plugin.getItemService());
    }

    /**
     * 空の売却 GUI を開きます。
     *
     * @param player 対象プレイヤー
     */
    public void open(@NotNull Player player) {
        open(player, List.of(), 0);
    }

    /**
     * 現在保持している売却内容で指定ページを開きます。
     *
     * @param player 対象プレイヤー
     * @param pageIndex 表示ページ
     */
    public void open(@NotNull Player player, int pageIndex) {
        open(player, pageIndex, false);
    }

    /**
     * 現在保持している売却内容で指定ページを開きます。
     *
     * @param player 対象プレイヤー
     * @param pageIndex 表示ページ
     * @param restoreAfterOpen GUI 表示後にプレイヤーインベントリを復元する場合は true
     */
    public void open(@NotNull Player player, int pageIndex, boolean restoreAfterOpen) {
        List<ItemStack> normalized = normalizeSellItems(
            sellItemsByPlayer.getOrDefault(player.getUniqueId(), List.of())
        );
        openInternal(player, normalized, pageIndex, restoreAfterOpen);
    }

    /**
     * 指定アイテム内容で売却 GUI を開きます。
     *
     * @param player 対象プレイヤー
     * @param sellItems 表示するアイテム一覧
     * @param pageIndex 表示ページ
     */
    public void open(@NotNull Player player, @NotNull List<ItemStack> sellItems, int pageIndex) {
        openInternal(player, normalizeSellItems(sellItems), pageIndex, false);
    }

    /**
     * 売却 GUI のクリックイベントを処理します。
     *
     * @param event クリックイベント
     */
    public void handleClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        MenuScreen screen = menuView.getMenuScreen(event.getView().getTopInventory());
        if (screen == MenuScreen.SELL) {
            handleSellClick(event, player);
            return;
        }
        if (screen == MenuScreen.SELL_CONFIRM) {
            handleSellConfirmClick(event, player);
        }
    }

    /**
     * 売却 GUI のドラッグイベントを処理します。
     *
     * @param event ドラッグイベント
     */
    public void handleDrag(@NotNull InventoryDragEvent event) {
        MenuScreen screen = menuView.getMenuScreen(event.getView().getTopInventory());
        if (event.getWhoClicked() instanceof Player player
            && !AccountModeGuard.isGameplayPlayer(player)) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        if (screen == MenuScreen.SELL) {
            if (event.getRawSlots().stream().anyMatch(this::isSellControlSlot)) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    GuiSound.DENY.play(player);
                }
            }
            return;
        }
        if (screen == MenuScreen.SELL_CONFIRM) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                GuiSound.DENY.play(player);
            }
        }
    }

    /**
     * 売却 GUI クローズ時の後処理を行います。
     *
     * @param inventory 閉じられたインベントリ
     * @param player 対象プレイヤー
     */
    public void handleClose(@NotNull Inventory inventory, @NotNull Player player) {
        MenuScreen screen = menuView.getMenuScreen(inventory);
        if (screen == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (screen == MenuScreen.SELL) {
            if (suppressSellConfirmOnClose.remove(playerId)) {
                List<ItemStack> items = snapshotSellItems(inventory);
                sellItemsByPlayer.put(playerId, items);
                return;
            }
            List<ItemStack> allItems = collectAllSellItems(inventory, playerId);
            ReturnSellItemsResult result = returnSellItemsToInventory(player, allItems);
            if (result.failedItems().isEmpty()) {
                discard(player);
            } else {
                List<ItemStack> failedItems = normalizeSellItems(result.failedItems());
                sellItemsByPlayer.put(playerId, failedItems);
                sellPageByPlayer.put(playerId, normalizeSellPage(0, failedItems.size()));
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        open(player, 0);
                    }
                });
            }
            if (!result.returnedItems().isEmpty()) {
                notifySellReturned(player, result.returnedItems());
            }
            return;
        }
        if (screen == MenuScreen.SELL_CONFIRM) {
            if (suppressSellConfirmOnClose.remove(playerId)) {
                if (!suppressSellConfirmRestoreOnClose.remove(playerId)) {
                    menuGuiTransitionService.restorePlayerInventory(player);
                }
                return;
            }
            menuGuiTransitionService.restorePlayerInventory(player);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                GuiSound.SELECT.play(player);
                open(player, 0);
            });
        }
    }

    private void openInternal(
        @NotNull Player player,
        @NotNull List<ItemStack> sellItems,
        int pageIndex,
        boolean restoreAfterOpen
    ) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            GuiSound.DENY.play(player);
            return;
        }
        int normalizedPage = normalizeSellPage(pageIndex, sellItems.size());
        sellItemsByPlayer.put(player.getUniqueId(), sellItems);
        sellPageByPlayer.put(player.getUniqueId(), normalizedPage);
        if (restoreAfterOpen) {
            menuGuiTransitionService.switchGuiWithInventoryRestore(
                player,
                () -> menuView.openSell(player, sellItems, normalizedPage)
            );
            return;
        }
        menuView.openSell(player, sellItems, normalizedPage);
    }

    private void openSellConfirm(@NotNull Player player, @NotNull List<ItemStack> currentItems, int pageIndex) {
        List<ItemStack> normalized = normalizeSellItems(currentItems);
        if (normalized.isEmpty()) {
            discard(player);
            return;
        }
        sellItemsByPlayer.put(player.getUniqueId(), normalized);
        suppressSellConfirmOnClose.add(player.getUniqueId());
        menuGuiTransitionService.switchGuiWithoutInventoryReload(player, () -> {
            menuView.openSellConfirm(player, normalized, pageIndex);
            menuGuiTransitionService.fillPlayerInventoryDummy(player);
        });
    }

    private void handleSellClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        Inventory topInventory = event.getView().getTopInventory();
        List<ItemStack> currentSellItems = collectAllSellItems(topInventory, player.getUniqueId());

        if (rawSlot >= topInventory.getSize()) {
            handleSellPlayerInventoryClick(event, player, topInventory);
            return;
        }

        if (rawSlot == MenuView.SELL_CONFIRM_SLOT) {
            if (currentSellItems.isEmpty()) {
                discard(player);
                suppressSellConfirmOnClose.add(player.getUniqueId());
                player.closeInventory();
                return;
            }
            openSellConfirm(player, currentSellItems, 0);
            return;
        }
        if (rawSlot == MenuView.SELL_PREVIOUS_SLOT) {
            int pageIndex = currentSellPage(player.getUniqueId(), topInventory);
            if (menuView.hasPreviousSellPage(pageIndex)) {
                GuiSound.SELECT.play(player);
                rerenderSellInventory(player, topInventory, pageIndex - 1);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (rawSlot == MenuView.SELL_NEXT_SLOT) {
            int pageIndex = currentSellPage(player.getUniqueId(), topInventory);
            if (menuView.hasNextSellPage(currentSellItems, pageIndex)) {
                GuiSound.SELECT.play(player);
                rerenderSellInventory(player, topInventory, pageIndex + 1);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (isSellContentSlot(rawSlot)) {
            handleSellContentClick(event, player, topInventory, rawSlot);
            return;
        }
        if (isSellControlSlot(rawSlot)) {
            GuiSound.DENY.play(player);
        }
    }

    private void handleSellConfirmClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        Inventory topInventory = event.getView().getTopInventory();
        List<ItemStack> currentSellItems = sellItemsByPlayer.getOrDefault(player.getUniqueId(), List.of());

        if (rawSlot >= topInventory.getSize()) {
            GuiSound.DENY.play(player);
            return;
        }

        if (rawSlot == MenuView.SELL_CONFIRM_SELL_SLOT) {
            List<ItemStack> soldItems = normalizeSellItems(currentSellItems);
            long totalSaleValue = totalSaleValue(soldItems);
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (!AccountModeGuard.isGameplayPlayer(astPlayer) || !inventoryService.addGold(astPlayer, totalSaleValue)) {
                GuiSound.DENY.play(player);
                player.updateInventory();
                return;
            }
            GuiSound.SELECT.play(player);
            discard(player);
            notifySellCompleted(player, soldItems, totalSaleValue);
            menuGuiTransitionService.restorePlayerInventory(player);
            suppressSellConfirmOnClose.add(player.getUniqueId());
            player.closeInventory();
            return;
        }
        if (rawSlot == MenuView.SELL_CONFIRM_RETURN_SLOT) {
            GuiSound.SELECT.play(player);
            suppressSellConfirmOnClose.add(player.getUniqueId());
            suppressSellConfirmRestoreOnClose.add(player.getUniqueId());
            menuGuiTransitionService.switchGuiWithInventoryRestore(player, () -> open(player, currentSellItems, 0));
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void handleSellPlayerInventoryClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull Inventory topInventory
    ) {
        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            GuiSound.DENY.play(player);
            return;
        }
        ItemModel sourceModel = inventoryService.getDisplayedItemModelAtBukkitSlot(astPlayer, event.getSlot());
        if (sourceModel == null) {
            GuiSound.DENY.play(player);
            player.updateInventory();
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            GuiSound.DENY.play(player);
            return;
        }
        if (sourceModel.getUnSellable()) {
            GuiSound.DENY.play(player);
            sendSellMessage(player, PlayerMsgId.P_5605);
            return;
        }
        int requested = ItemTransferSupport.resolveTransferAmount(event.getClick(), clicked.getAmount());
        if (requested <= 0) {
            GuiSound.DENY.play(player);
            return;
        }
        int capacity = countSellPlacementCapacity(topInventory, clicked, requested);
        if (capacity <= 0) {
            GuiSound.DENY.play(player);
            player.updateInventory();
            return;
        }
        ItemStack moved = inventoryService.takeDisplayedItemAmount(astPlayer, event.getSlot(), capacity);
        if (moved == null || moved.getType() == Material.AIR) {
            GuiSound.DENY.play(player);
            player.updateInventory();
            return;
        }
        placeItemsIntoSell(topInventory, moved);
        GuiSound.SELECT.play(player);
        rerenderSellInventory(player, topInventory);
        player.updateInventory();
    }

    private void handleSellContentClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull Inventory topInventory,
        int rawSlot
    ) {
        ItemStack current = topInventory.getItem(rawSlot);
        ItemStack cursor = event.getCursor();
        boolean hasCurrent = !isSellEmptyItem(topInventory, current);
        boolean hasCursor = cursor != null && cursor.getType() != Material.AIR;

        if (hasCursor) {
            GuiSound.DENY.play(player);
            player.updateInventory();
            return;
        }
        if (!hasCurrent) {
            GuiSound.DENY.play(player);
            return;
        }

        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            GuiSound.DENY.play(player);
            return;
        }
        int requested = ItemTransferSupport.resolveTransferAmount(event.getClick(), current.getAmount());
        if (requested <= 0) {
            GuiSound.DENY.play(player);
            return;
        }
        ItemStack partial = stripTransferDisplayLore(current);
        partial.setAmount(requested);
        ItemReference reference = resolveTransferReference(partial);
        if (reference == null || inventoryService.returnItemToOwnedInventory(astPlayer, reference, requested) == null) {
            GuiSound.DENY.play(player);
            player.updateInventory();
            return;
        }
        int remaining = current.getAmount() - requested;
        if (remaining <= 0) {
            topInventory.setItem(rawSlot, new ItemStack(Material.AIR));
        } else {
            ItemStack updated = stripTransferDisplayLore(current);
            updated.setAmount(remaining);
            topInventory.setItem(rawSlot, updated);
        }
        GuiSound.SELECT.play(player);
        rerenderSellInventory(player, topInventory);
        player.updateInventory();
    }

    private void rerenderSellInventory(@NotNull Player player, @NotNull Inventory topInventory) {
        rerenderSellInventory(player, topInventory, currentSellPage(player.getUniqueId(), topInventory));
    }

    private void rerenderSellInventory(@NotNull Player player, @NotNull Inventory topInventory, int pageIndex) {
        List<ItemStack> currentItems = collectAllSellItems(topInventory, player.getUniqueId());
        int normalizedPage = normalizeSellPage(pageIndex, currentItems.size());
        sellItemsByPlayer.put(player.getUniqueId(), currentItems);
        sellPageByPlayer.put(player.getUniqueId(), normalizedPage);
        menuView.renderSell(topInventory, currentItems, normalizedPage);
    }

    private @NotNull List<ItemStack> collectAllSellItems(@NotNull Inventory inventory, @NotNull UUID playerId) {
        int pageIndex = currentSellPage(playerId, inventory);
        List<ItemStack> currentPage = snapshotSellItems(inventory);
        List<ItemStack> existing = sellItemsByPlayer.getOrDefault(playerId, List.of());
        return normalizeSellItems(ItemTransferSupport.mergePagedItems(
            pageIndex,
            SellScreenView.CONTENT_SLOT_COUNT,
            existing,
            currentPage
        ));
    }

    private int currentSellPage(@NotNull UUID playerId, @NotNull Inventory inventory) {
        return sellPageByPlayer.getOrDefault(playerId, menuView.getPageIndex(inventory));
    }

    private int normalizeSellPage(int pageIndex, int itemCount) {
        return GuiPagination.normalizePage(pageIndex, itemCount, SellScreenView.CONTENT_SLOT_COUNT);
    }

    private @NotNull ReturnSellItemsResult returnSellItemsToInventory(
        @NotNull Player player,
        @NotNull List<ItemStack> items
    ) {
        if (items.isEmpty()) {
            return ReturnSellItemsResult.empty();
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return new ReturnSellItemsResult(List.of(), new ArrayList<>(items));
        }
        List<ItemStack> returnedItems = new ArrayList<>();
        List<ItemStack> failedItems = new ArrayList<>();
        for (ItemStack itemStack : items) {
            if (itemStack == null || itemStack.getType() == Material.AIR) {
                continue;
            }
            ItemStack cleanItem = stripTransferDisplayLore(itemStack);
            ItemModel model = resolveTransferItemModel(cleanItem);
            if (model == null
                || !inventoryService.canAddItemToNormalInventory(astPlayer, model, Math.max(1, cleanItem.getAmount()))
                || inventoryService.returnItemToOwnedInventory(astPlayer, cleanItem.clone()) == null) {
                failedItems.add(cleanItem);
                continue;
            }
            returnedItems.add(cleanItem);
        }
        return new ReturnSellItemsResult(returnedItems, failedItems);
    }

    private void notifySellCompleted(@NotNull Player player, @NotNull List<ItemStack> items, long totalSaleValue) {
        int soldCount = countSellItemStacks(items);
        if (soldCount <= 0) {
            return;
        }
        sendSellMessage(player, PlayerMsgId.P_5604, soldCount, totalSaleValue);
    }

    private void notifySellReturned(@NotNull Player player, @NotNull List<ItemStack> items) {
        int returnedCount = countSellItemStacks(items);
        if (returnedCount <= 0) {
            return;
        }
        GuiSound.SELECT.play(player);
        sendSellMessage(player, PlayerMsgId.P_5606, returnedCount);
    }

    private int countSellItemStacks(@NotNull List<ItemStack> items) {
        return ItemTransferSupport.countStacks(items, this::isSellEmptyItem);
    }

    private void sendSellMessage(@NotNull Player player, @NotNull PlayerMsgId msgId, Object... args) {
        PlayerMessageService.getInstance().send(player, msgId, args);
    }

    private @NotNull List<ItemStack> snapshotSellItems(@NotNull Inventory inventory) {
        return normalizeSellItems(ItemTransferSupport.snapshotContent(
            inventory,
            SellScreenView.CONTENT_SLOT_COUNT,
            this::isSellEmptyItem,
            this::stripTransferDisplayLore
        ));
    }

    private @NotNull List<ItemStack> normalizeSellItems(@NotNull List<ItemStack> items) {
        return ItemTransferSupport.normalize(
            items,
            this::isSellEmptyItem,
            this::stripTransferDisplayLore,
            this::resolveTransferReference
        );
    }

    private int countSellPlacementCapacity(
        @NotNull Inventory topInventory,
        @NotNull ItemStack template,
        int desired
    ) {
        return ItemTransferSupport.countPlacementCapacity(
            topInventory,
            SellScreenView.CONTENT_SLOT_COUNT,
            template,
            desired,
            this::isSellEmptyItem,
            this::stripTransferDisplayLore
        );
    }

    private void placeItemsIntoSell(@NotNull Inventory topInventory, @NotNull ItemStack moved) {
        ItemTransferSupport.placeIntoContent(
            topInventory,
            SellScreenView.CONTENT_SLOT_COUNT,
            moved,
            this::isSellEmptyItem,
            this::stripTransferDisplayLore
        );
    }

    private long totalSaleValue(@NotNull List<ItemStack> items) {
        long total = 0L;
        for (ItemStack itemStack : items) {
            if (isSellEmptyItem(null, itemStack)) {
                continue;
            }
            ItemModel model = resolveTransferItemModel(itemStack);
            if (model == null || model.getUnSellable()) {
                continue;
            }
            total += (long) Math.max(0, model.getSaleValue()) * Math.max(1, itemStack.getAmount());
        }
        return total;
    }

    private @Nullable ItemReference resolveTransferReference(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return null;
        }
        return transferItemResolver.resolve(stripTransferDisplayLore(itemStack));
    }

    private @Nullable ItemModel resolveTransferItemModel(@Nullable ItemStack itemStack) {
        return transferItemResolver.resolveItemModel(resolveTransferReference(itemStack));
    }

    private @NotNull ItemStack stripTransferDisplayLore(@NotNull ItemStack itemStack) {
        return ItemTransferSupport.stripDisplayLore(
            itemStack,
            BaseMenuScreenView.DISPLAY_AMOUNT_LORE_PREFIX,
            SellScreenView.UNIT_PRICE_LORE_PREFIX,
            SellScreenView.TOTAL_PRICE_LORE_PREFIX
        );
    }

    private boolean isSellEmptyItem(@Nullable Inventory inventory, @Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return true;
        }
        return isSellPlaceholderItem(inventory, itemStack);
    }

    private boolean isSellPlaceholderItem(@Nullable Inventory inventory, @Nullable ItemStack itemStack) {
        if (itemStack == null) {
            return false;
        }
        MenuScreen screen = inventory == null ? null : menuView.getMenuScreen(inventory);
        if (screen == MenuScreen.SELL) {
            return menuView.isSellContentPlaceholder(itemStack);
        }
        if (screen == MenuScreen.SELL_CONFIRM) {
            return menuView.isSellConfirmContentPlaceholder(itemStack);
        }
        return menuView.isSellContentPlaceholder(itemStack)
            || menuView.isSellConfirmContentPlaceholder(itemStack);
    }

    private boolean isSellContentSlot(int rawSlot) {
        return ItemTransferSupport.isContentSlot(rawSlot, SellScreenView.CONTENT_SLOT_COUNT);
    }

    private boolean isSellControlSlot(int rawSlot) {
        return rawSlot == MenuView.SELL_PREVIOUS_SLOT
            || rawSlot == MenuView.SELL_GUIDE_SLOT
            || rawSlot == MenuView.SELL_CONFIRM_SLOT
            || rawSlot == MenuView.SELL_NEXT_SLOT;
    }

    private void discard(@NotNull Player player) {
        sellItemsByPlayer.remove(player.getUniqueId());
        sellPageByPlayer.remove(player.getUniqueId());
    }

    private record ReturnSellItemsResult(
        @NotNull List<ItemStack> returnedItems,
        @NotNull List<ItemStack> failedItems
    ) {
        private static @NotNull ReturnSellItemsResult empty() {
            return new ReturnSellItemsResult(List.of(), List.of());
        }
    }
}
