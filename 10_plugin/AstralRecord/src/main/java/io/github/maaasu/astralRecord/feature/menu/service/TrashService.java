package io.github.maaasu.astralRecord.feature.menu.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.service.ItemReferenceResolver;
import io.github.maaasu.astralRecord.feature.item.service.ItemTransferSupport;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.TrashScreenView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
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
 * ゴミ箱 GUI の状態管理とイベント処理を担当するサービスです。
 */
public final class TrashService {
    private final AstralRecord plugin;
    private final MenuView menuView;
    private final InventoryService inventoryService;
    private final MenuGuiTransitionService menuGuiTransitionService;
    private final ItemReferenceResolver transferItemResolver;
    private final ConcurrentHashMap<UUID, List<ItemStack>> trashItemsByPlayer = new ConcurrentHashMap<>();
    private final Set<UUID> suppressTrashConfirmOnClose = ConcurrentHashMap.newKeySet();
    private final Set<UUID> suppressTrashConfirmRestoreOnClose = ConcurrentHashMap.newKeySet();

    /**
     * ゴミ箱 GUI サービスを初期化します。
     *
     * @param plugin プラグイン本体
     * @param menuView メニュー GUI 表示
     * @param inventoryService インベントリサービス
     * @param menuGuiTransitionService GUI 切替サービス
     */
    public TrashService(
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
     * 空のゴミ箱 GUI を開きます。
     *
     * @param player 対象プレイヤー
     */
    public void open(@NotNull Player player) {
        open(player, List.of(), 0);
    }

    /**
     * 現在保持しているゴミ箱内容で指定ページを開きます。
     *
     * @param player 対象プレイヤー
     * @param pageIndex 表示ページ
     */
    public void open(@NotNull Player player, int pageIndex) {
        open(player, pageIndex, false);
    }

    /**
     * 現在保持しているゴミ箱内容で指定ページを開きます。
     *
     * @param player 対象プレイヤー
     * @param pageIndex 表示ページ
     * @param restoreAfterOpen GUI 表示後にプレイヤーインベントリを復元する場合は true
     */
    public void open(@NotNull Player player, int pageIndex, boolean restoreAfterOpen) {
        List<ItemStack> normalized = normalizeTrashItems(
            trashItemsByPlayer.getOrDefault(player.getUniqueId(), List.of())
        );
        openInternal(player, normalized, pageIndex, restoreAfterOpen);
    }

    /**
     * 指定アイテム内容でゴミ箱 GUI を開きます。
     *
     * @param player 対象プレイヤー
     * @param trashItems 表示するアイテム一覧
     * @param pageIndex 表示ページ
     */
    public void open(@NotNull Player player, @NotNull List<ItemStack> trashItems, int pageIndex) {
        openInternal(player, normalizeTrashItems(trashItems), pageIndex, false);
    }

    /**
     * ゴミ箱 GUI のクリックイベントを処理します。
     *
     * @param event クリックイベント
     */
    public void handleClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        MenuScreen screen = menuView.getMenuScreen(event.getView().getTopInventory());
        if (screen == MenuScreen.TRASH) {
            handleTrashClick(event, player);
            return;
        }
        if (screen == MenuScreen.TRASH_CONFIRM) {
            handleTrashConfirmClick(event, player);
        }
    }

    /**
     * ゴミ箱 GUI のドラッグイベントを処理します。
     *
     * @param event ドラッグイベント
     */
    public void handleDrag(@NotNull InventoryDragEvent event) {
        MenuScreen screen = menuView.getMenuScreen(event.getView().getTopInventory());
        if (screen == MenuScreen.TRASH) {
            if (event.getRawSlots().stream().anyMatch(this::isTrashControlSlot)) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    GuiSound.DENY.play(player);
                }
            }
            return;
        }
        if (screen == MenuScreen.TRASH_CONFIRM) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                GuiSound.DENY.play(player);
            }
        }
    }

    /**
     * ゴミ箱 GUI クローズ時の後処理を行います。
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
        if (screen == MenuScreen.TRASH) {
            if (suppressTrashConfirmOnClose.remove(playerId)) {
                List<ItemStack> items = snapshotTrashItems(inventory);
                trashItemsByPlayer.put(playerId, items);
                return;
            }
            List<ItemStack> allItems = collectAllTrashItems(inventory, playerId);
            boolean returned = returnTrashItemsToInventory(player, allItems);
            discard(player);
            if (returned) {
                notifyTrashReturned(player, allItems);
            }
            return;
        }
        if (screen == MenuScreen.TRASH_CONFIRM) {
            if (suppressTrashConfirmOnClose.remove(playerId)) {
                if (!suppressTrashConfirmRestoreOnClose.remove(playerId)) {
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
        @NotNull List<ItemStack> trashItems,
        int pageIndex,
        boolean restoreAfterOpen
    ) {
        trashItemsByPlayer.put(player.getUniqueId(), trashItems);
        if (restoreAfterOpen) {
            menuGuiTransitionService.switchGuiWithInventoryRestore(
                player,
                () -> menuView.openTrash(player, trashItems, pageIndex)
            );
            return;
        }
        menuView.openTrash(player, trashItems, pageIndex);
    }

    private void openTrashConfirm(@NotNull Player player, @NotNull List<ItemStack> currentItems, int pageIndex) {
        List<ItemStack> normalized = normalizeTrashItems(currentItems);
        if (normalized.isEmpty()) {
            discard(player);
            return;
        }
        trashItemsByPlayer.put(player.getUniqueId(), normalized);
        suppressTrashConfirmOnClose.add(player.getUniqueId());
        menuView.openTrashConfirm(player, normalized, pageIndex);
        menuGuiTransitionService.fillPlayerInventoryDummy(player);
    }

    private void handleTrashClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        Inventory topInventory = event.getView().getTopInventory();
        List<ItemStack> currentTrashItems = snapshotTrashItems(topInventory);

        if (rawSlot >= topInventory.getSize()) {
            handleTrashPlayerInventoryClick(event, player, topInventory);
            return;
        }

        if (rawSlot == MenuView.BACK_SLOT) {
            suppressTrashConfirmOnClose.add(player.getUniqueId());
            GuiSound.SELECT.play(player);
            menuGuiTransitionService.switchGuiWithInventoryRestore(player, () -> menuView.open(player));
            return;
        }
        if (rawSlot == MenuView.TRASH_CONFIRM_SLOT) {
            if (currentTrashItems.isEmpty()) {
                discard(player);
                suppressTrashConfirmOnClose.add(player.getUniqueId());
                player.closeInventory();
                return;
            }
            openTrashConfirm(player, currentTrashItems, 0);
            return;
        }
        if (rawSlot == MenuView.TRASH_PREVIOUS_SLOT) {
            int pageIndex = menuView.getPageIndex(topInventory);
            if (menuView.hasPreviousTrashPage(pageIndex)) {
                GuiSound.SELECT.play(player);
                open(player, pageIndex - 1);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (rawSlot == MenuView.TRASH_NEXT_SLOT) {
            int pageIndex = menuView.getPageIndex(topInventory);
            if (menuView.hasNextTrashPage(currentTrashItems, pageIndex)) {
                GuiSound.SELECT.play(player);
                open(player, pageIndex + 1);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (isTrashContentSlot(rawSlot)) {
            handleTrashContentClick(event, player, topInventory, rawSlot);
            return;
        }
        if (isTrashControlSlot(rawSlot)) {
            GuiSound.DENY.play(player);
        }
    }

    private void handleTrashConfirmClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        Inventory topInventory = event.getView().getTopInventory();
        List<ItemStack> currentTrashItems = trashItemsByPlayer.getOrDefault(player.getUniqueId(), List.of());

        if (rawSlot >= topInventory.getSize()) {
            GuiSound.DENY.play(player);
            return;
        }

        if (rawSlot == MenuView.TRASH_CONFIRM_DISPOSE_SLOT) {
            List<ItemStack> disposedItems = normalizeTrashItems(currentTrashItems);
            GuiSound.TRASH_DISPOSE.play(player);
            discard(player);
            notifyTrashDisposed(player, disposedItems);
            menuGuiTransitionService.restorePlayerInventory(player);
            suppressTrashConfirmOnClose.add(player.getUniqueId());
            player.closeInventory();
            return;
        }
        if (rawSlot == MenuView.TRASH_CONFIRM_RETURN_SLOT) {
            GuiSound.SELECT.play(player);
            suppressTrashConfirmOnClose.add(player.getUniqueId());
            suppressTrashConfirmRestoreOnClose.add(player.getUniqueId());
            open(player, currentTrashItems, 0);
            menuGuiTransitionService.restorePlayerInventory(player);
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void handleTrashPlayerInventoryClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull Inventory topInventory
    ) {
        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            GuiSound.DENY.play(player);
            return;
        }
        int requested = ItemTransferSupport.resolveTransferAmount(event.getClick(), clicked.getAmount());
        if (requested <= 0) {
            GuiSound.DENY.play(player);
            return;
        }
        ItemStack moved = inventoryService.takeOwnedItemAmount(astPlayer, event.getSlot(), requested);
        if (moved == null || moved.getType() == Material.AIR) {
            GuiSound.DENY.play(player);
            player.updateInventory();
            return;
        }
        List<ItemStack> updatedItems = new ArrayList<>(collectAllTrashItems(topInventory, player.getUniqueId()));
        updatedItems.add(moved);
        updatedItems = normalizeTrashItems(updatedItems);
        trashItemsByPlayer.put(player.getUniqueId(), updatedItems);
        GuiSound.SELECT.play(player);
        rerenderTrashInventory(player, topInventory, updatedItems);
        player.updateInventory();
    }

    private void handleTrashContentClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull Inventory topInventory,
        int rawSlot
    ) {
        ItemStack current = topInventory.getItem(rawSlot);
        ItemStack cursor = event.getCursor();
        boolean hasCurrent = !isTrashEmptyItem(topInventory, current);
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
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        int requested = ItemTransferSupport.resolveTransferAmount(event.getClick(), current.getAmount());
        if (requested <= 0) {
            GuiSound.DENY.play(player);
            return;
        }
        ItemStack partial = stripTrashDisplayAmountLore(current);
        partial.setAmount(requested);
        ItemReference reference = resolveTransferReference(partial);
        if (reference == null || inventoryService.returnItemToOwnedInventory(astPlayer, reference, requested) == null) {
            GuiSound.DENY.play(player);
            player.updateInventory();
            return;
        }
        List<ItemStack> updatedItems = collectAllTrashItems(topInventory, player.getUniqueId());
        int itemIndex = menuView.getPageIndex(topInventory) * TrashScreenView.CONTENT_SLOT_COUNT + rawSlot;
        if (itemIndex >= 0 && itemIndex < updatedItems.size()) {
            ItemStack updated = stripTrashDisplayAmountLore(updatedItems.get(itemIndex));
            int remaining = updated.getAmount() - requested;
            updatedItems = new ArrayList<>(updatedItems);
            if (remaining <= 0) {
                updatedItems.remove(itemIndex);
            } else {
                updated.setAmount(remaining);
                updatedItems.set(itemIndex, updated);
            }
        }
        updatedItems = normalizeTrashItems(updatedItems);
        trashItemsByPlayer.put(player.getUniqueId(), updatedItems);
        GuiSound.SELECT.play(player);
        rerenderTrashInventory(player, topInventory, updatedItems);
        player.updateInventory();
    }

    private void rerenderTrashInventory(
        @NotNull Player player,
        @NotNull Inventory topInventory,
        @NotNull List<ItemStack> trashItems
    ) {
        int pageIndex = menuView.getPageIndex(topInventory);
        List<ItemStack> normalized = normalizeTrashItems(trashItems);
        trashItemsByPlayer.put(player.getUniqueId(), normalized);
        menuView.renderTrash(topInventory, normalized, pageIndex);
    }

    private @NotNull List<ItemStack> collectAllTrashItems(@NotNull Inventory inventory, @NotNull UUID playerId) {
        int pageIndex = menuView.getPageIndex(inventory);
        List<ItemStack> currentPage = snapshotTrashItems(inventory);
        List<ItemStack> existing = trashItemsByPlayer.getOrDefault(playerId, List.of());
        return normalizeTrashItems(ItemTransferSupport.mergePagedItems(
            pageIndex,
            TrashScreenView.CONTENT_SLOT_COUNT,
            existing,
            currentPage
        ));
    }

    private boolean returnTrashItemsToInventory(@NotNull Player player, @NotNull List<ItemStack> items) {
        if (items.isEmpty()) {
            return false;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return false;
        }
        for (ItemStack itemStack : items) {
            if (itemStack == null || itemStack.getType() == Material.AIR) {
                continue;
            }
            inventoryService.returnItemToOwnedInventory(astPlayer, itemStack.clone());
        }
        return true;
    }

    private void notifyTrashDisposed(@NotNull Player player, @NotNull List<ItemStack> items) {
        int disposedCount = countTrashItemStacks(items);
        if (disposedCount <= 0) {
            return;
        }
        sendTrashMessage(player, PlayerMsgId.P_5601, disposedCount);
    }

    private void notifyTrashReturned(@NotNull Player player, @NotNull List<ItemStack> items) {
        int returnedCount = countTrashItemStacks(items);
        if (returnedCount <= 0) {
            return;
        }
        GuiSound.SELECT.play(player);
        sendTrashMessage(player, PlayerMsgId.P_5602, returnedCount);
    }

    private int countTrashItemStacks(@NotNull List<ItemStack> items) {
        return ItemTransferSupport.countStacks(items, this::isTrashEmptyItem);
    }

    private void sendTrashMessage(@NotNull Player player, @NotNull PlayerMsgId msgId, Object... args) {
        PlayerMessageService.getInstance().send(player, msgId, args);
    }

    private @NotNull List<ItemStack> snapshotTrashItems(@NotNull Inventory inventory) {
        return normalizeTrashItems(ItemTransferSupport.snapshotContent(
            inventory,
            TrashScreenView.CONTENT_SLOT_COUNT,
            this::isTrashEmptyItem,
            this::stripTrashDisplayAmountLore
        ));
    }

    private @NotNull List<ItemStack> normalizeTrashItems(@NotNull List<ItemStack> items) {
        return ItemTransferSupport.normalize(
            items,
            this::isTrashEmptyItem,
            this::stripTransferDisplayLore,
            this::resolveTransferReference
        );
    }

    private @Nullable ItemReference resolveTransferReference(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return null;
        }
        return transferItemResolver.resolve(stripTransferDisplayLore(itemStack));
    }

    private @NotNull ItemStack stripTrashDisplayAmountLore(@NotNull ItemStack itemStack) {
        return stripTransferDisplayLore(itemStack);
    }

    private @NotNull ItemStack stripTransferDisplayLore(@NotNull ItemStack itemStack) {
        return ItemTransferSupport.stripDisplayLore(itemStack, BaseMenuScreenView.DISPLAY_AMOUNT_LORE_PREFIX);
    }

    private boolean isTrashEmptyItem(@Nullable Inventory inventory, @Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return true;
        }
        return isTrashPlaceholderItem(inventory, itemStack);
    }

    private boolean isTrashPlaceholderItem(@Nullable Inventory inventory, @Nullable ItemStack itemStack) {
        if (itemStack == null) {
            return false;
        }
        MenuScreen screen = inventory == null ? null : menuView.getMenuScreen(inventory);
        if (screen == MenuScreen.TRASH) {
            return menuView.isTrashContentPlaceholder(itemStack);
        }
        if (screen == MenuScreen.TRASH_CONFIRM) {
            return menuView.isTrashConfirmContentPlaceholder(itemStack);
        }
        return menuView.isTrashContentPlaceholder(itemStack)
            || menuView.isTrashConfirmContentPlaceholder(itemStack);
    }

    private boolean isTrashContentSlot(int rawSlot) {
        return ItemTransferSupport.isContentSlot(rawSlot, TrashScreenView.CONTENT_SLOT_COUNT);
    }

    private boolean isTrashControlSlot(int rawSlot) {
        return rawSlot == MenuView.TRASH_PREVIOUS_SLOT
            || rawSlot == MenuView.TRASH_GUIDE_SLOT
            || rawSlot == MenuView.BACK_SLOT
            || rawSlot == MenuView.TRASH_CONFIRM_SLOT
            || rawSlot == MenuView.TRASH_NEXT_SLOT;
    }

    private void discard(@NotNull Player player) {
        trashItemsByPlayer.remove(player.getUniqueId());
    }
}
