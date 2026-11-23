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
     * 未確定のゴミ箱に指定アイテムがあるかを主スレッドで確認します。
     *
     * @param playerId 対象プレイヤーのUUID
     * @param itemId 対象アイテムID
     * @return 指定アイテムが一時保管されている場合は {@code true}
     */
    public boolean containsPendingItem(@NotNull UUID playerId, @NotNull String itemId) {
        for (ItemStack item : trashItemsByPlayer.getOrDefault(playerId, List.of())) {
            ItemReference reference = resolveTransferReference(item);
            if (reference != null && itemId.equalsIgnoreCase(reference.itemId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * ゴミ箱 GUI を開き、前回返却できなかった保護中の装備があれば表示します。
     *
     * @param player 対象プレイヤー
     */
    public void open(@NotNull Player player) {
        open(player, 0);
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
     * ゴミ箱 GUI のドラッグイベントを処理し、保護中の装備の取り込みを拒否します。
     *
     * @param event ドラッグイベント
     */
    public void handleDrag(@NotNull InventoryDragEvent event) {
        MenuScreen screen = menuView.getMenuScreen(event.getView().getTopInventory());
        if (screen == MenuScreen.TRASH) {
            if (event.getWhoClicked() instanceof Player player
                && event.getRawSlots().stream().anyMatch(this::isTrashContentSlot)
                && inventoryService.isEquipmentProtected(event.getOldCursor())) {
                event.setCancelled(true);
                GuiSound.DENY.play(player);
                sendTrashMessage(player, PlayerMsgId.P_5680);
                return;
            }
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
     * ゴミ箱 GUI クローズ時にアイテムを返却し、返却できない保護中の装備を保持します。
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
            List<ItemStack> allItems = collectAllTrashItems(inventory, playerId);
            closeTrashAndReturnItems(player, allItems);
            return;
        }
        if (screen == MenuScreen.TRASH_CONFIRM) {
            List<ItemStack> allItems = normalizeTrashItems(
                trashItemsByPlayer.getOrDefault(playerId, List.of())
            );
            closeTrashAndReturnItems(player, allItems);
            menuGuiTransitionService.restorePlayerInventory(player);
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
        GuiSound.CONFIRM.play(player);
        menuView.openTrashConfirm(player, normalized, pageIndex);
        menuGuiTransitionService.fillPlayerInventoryDummy(player);
    }

    /**
     * ゴミ箱の編集操作と、全ページを対象とする処分確認への遷移を処理します。
     *
     * @param event クリックイベント
     * @param player 操作プレイヤー
     */
    private void handleTrashClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        Inventory topInventory = event.getView().getTopInventory();

        if (rawSlot >= topInventory.getSize()) {
            handleTrashPlayerInventoryClick(event, player, topInventory);
            return;
        }

        List<ItemStack> currentTrashItems = collectAllTrashItems(topInventory, player.getUniqueId());
        if (rawSlot == MenuView.BACK_SLOT) {
            if (!closeTrashAndReturnItems(player, currentTrashItems)) {
                rerenderTrashInventory(player, topInventory, trashItemsByPlayer.get(player.getUniqueId()));
                return;
            }
            menuGuiTransitionService.switchGuiWithInventoryRestore(
                player,
                () -> plugin.getGuiNavigationService().openPrevious(player)
            );
            return;
        }
        if (rawSlot == MenuView.TRASH_CONFIRM_SLOT) {
            if (currentTrashItems.isEmpty()) {
                discard(player);
                player.closeInventory();
                return;
            }
            openTrashConfirm(player, currentTrashItems, 0);
            return;
        }
        if (rawSlot == MenuView.TRASH_PREVIOUS_SLOT) {
            int pageIndex = menuView.getPageIndex(topInventory);
            if (menuView.hasPreviousTrashPage(pageIndex)) {
                GuiSound.PAGE.play(player);
                open(player, pageIndex - 1);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (rawSlot == MenuView.TRASH_NEXT_SLOT) {
            int pageIndex = menuView.getPageIndex(topInventory);
            if (menuView.hasNextTrashPage(currentTrashItems, pageIndex)) {
                GuiSound.PAGE.play(player);
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

    /**
     * ゴミ箱の確定・返却を処理します。保護中の装備がある場合は処分を確定せず、返却を許可します。
     *
     * @param event クリックイベント
     * @param player 操作プレイヤー
     */
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
            if (disposedItems.stream().anyMatch(inventoryService::isEquipmentProtected)) {
                GuiSound.DENY.play(player);
                sendTrashMessage(player, PlayerMsgId.P_5680);
                return;
            }
            GuiSound.SELECT.play(player);
            GuiSound.TRASH_DISPOSE.play(player);
            discard(player);
            notifyTrashDisposed(player, disposedItems);
            menuGuiTransitionService.restorePlayerInventory(player);
            player.closeInventory();
            return;
        }
        if (rawSlot == MenuView.TRASH_CONFIRM_RETURN_SLOT) {
            GuiSound.SELECT.play(player);
            if (!closeTrashAndReturnItems(player, currentTrashItems)) {
                return;
            }
            menuGuiTransitionService.switchGuiWithInventoryRestore(
                player,
                () -> plugin.getGuiNavigationService().openPrevious(
                    player,
                    () -> clearReturnedTrashScreen(player),
                    () -> closeReturnedTrashConfirmScreen(player, topInventory)
                )
            );
            return;
        }
        GuiSound.DENY.play(player);
    }

    /**
     * 戻り先のゴミ箱編集画面を空状態へ再描画します。
     *
     * <p>確認画面で返却した item が表示へ残ると、次の確認操作で再取り込みされるため、
     * 戻り先が実際にゴミ箱編集画面になった場合だけ表示内容を消去します。</p>
     *
     * @param player 対象プレイヤー
     */
    private void clearReturnedTrashScreen(@NotNull Player player) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        if (menuView.getMenuScreen(inventory) == MenuScreen.TRASH) {
            menuView.renderTrash(inventory, List.of(), 0);
        }
    }

    /**
     * 戻り先への遷移に失敗した返却済みゴミ箱確認画面を閉じます。
     *
     * <p>別の GUI がすでに開かれている場合は、その GUI を閉じないよう何もしません。</p>
     *
     * @param player 対象プレイヤー
     * @param returnedConfirmInventory 返却処理を開始した確認画面 inventory
     */
    private void closeReturnedTrashConfirmScreen(
        @NotNull Player player,
        @NotNull Inventory returnedConfirmInventory
    ) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        if (inventory == returnedConfirmInventory
            && menuView.getMenuScreen(inventory) == MenuScreen.TRASH_CONFIRM) {
            player.closeInventory();
        }
    }

    /**
     * 所持品の正本スロットを確認し、保護中の装備を除いてゴミ箱へ取り込みます。
     *
     * @param event クリックイベント
     * @param player 操作プレイヤー
     * @param topInventory ゴミ箱の表示領域
     */
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
        int requested = ItemTransferSupport.resolveTransferAmount(
            event.getClick(),
            clicked.getAmount(),
            clicked.getMaxStackSize()
        );
        if (requested <= 0) {
            GuiSound.DENY.play(player);
            return;
        }
        if (inventoryService.isOwnedEquipmentProtected(astPlayer, event.getSlot())) {
            GuiSound.DENY.play(player);
            sendTrashMessage(player, PlayerMsgId.P_5680);
            return;
        }
        ItemStack moved = inventoryService.takeUnprotectedOwnedItemAmount(astPlayer, event.getSlot(), requested, false);
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

    /**
     * 保留中の品を所有者へ返却します。保護中の装備はバッグの容量外にも返却できます。
     *
     * @param event クリックイベント
     * @param player 操作プレイヤー
     * @param topInventory 保留品の表示領域
     * @param rawSlot 対象品の表示スロット
     */
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
        int requested = ItemTransferSupport.resolveTransferAmount(
            event.getClick(),
            current.getAmount(),
            current.getMaxStackSize()
        );
        if (requested <= 0) {
            GuiSound.DENY.play(player);
            return;
        }
        ItemStack partial = stripTrashDisplayAmountLore(current);
        partial.setAmount(requested);
        boolean returned = inventoryService.isEquipmentProtected(partial)
            ? inventoryService.returnProtectedEquipmentToOwnedInventory(astPlayer, partial) != null
            : inventoryService.returnItemToOwnedInventory(astPlayer, resolveTransferReference(partial), requested) != null;
        if (!returned) {
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

    /**
     * 所持品への返却を試み、返却先を確保できない保護中の装備だけをゴミ箱の保留状態へ残します。
     *
     * @param player 所有者
     * @param items 返却対象
     * @return 保留品がなければ true
     */
    private boolean closeTrashAndReturnItems(@NotNull Player player, @NotNull List<ItemStack> items) {
        ReturnTrashItemsResult result = returnTrashItemsToInventory(player, items);
        List<ItemStack> protectedItems = new ArrayList<>();
        for (ItemStack failedItem : result.failedItems()) {
            if (inventoryService.isEquipmentProtected(failedItem)) {
                protectedItems.add(failedItem);
            } else if (failedItem != null && !failedItem.getType().isAir()) {
                player.getWorld().dropItemNaturally(player.getLocation(), failedItem.clone());
            }
        }
        if (protectedItems.isEmpty()) {
            discard(player);
        } else {
            trashItemsByPlayer.put(player.getUniqueId(), protectedItems);
            sendTrashMessage(player, PlayerMsgId.P_5680);
        }
        if (!result.returnedItems().isEmpty()) {
            notifyTrashReturned(player, result.returnedItems());
        }
        return protectedItems.isEmpty();
    }

    /**
     * ゴミ箱の保留品を所持品へ戻し、保護中の装備には満杯時の返却経路を使用します。
     *
     * @param player 所有者
     * @param items 返却対象
     * @return 返却結果
     */
    private @NotNull ReturnTrashItemsResult returnTrashItemsToInventory(
        @NotNull Player player,
        @NotNull List<ItemStack> items
    ) {
        if (items.isEmpty()) {
            return ReturnTrashItemsResult.empty();
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return new ReturnTrashItemsResult(List.of(), new ArrayList<>(items));
        }
        List<ItemStack> returnedItems = new ArrayList<>();
        List<ItemStack> failedItems = new ArrayList<>();
        for (ItemStack itemStack : items) {
            if (itemStack == null || itemStack.getType() == Material.AIR) {
                continue;
            }
            ItemStack cleanItem = stripTransferDisplayLore(itemStack);
            boolean returned = inventoryService.isEquipmentProtected(cleanItem)
                ? inventoryService.returnProtectedEquipmentToOwnedInventory(astPlayer, cleanItem) != null
                : inventoryService.returnItemToOwnedInventory(astPlayer, cleanItem) != null;
            if (!returned) {
                failedItems.add(cleanItem);
                continue;
            }
            returnedItems.add(cleanItem);
        }
        return new ReturnTrashItemsResult(returnedItems, failedItems);
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

    private record ReturnTrashItemsResult(
        @NotNull List<ItemStack> returnedItems,
        @NotNull List<ItemStack> failedItems
    ) {
        private static @NotNull ReturnTrashItemsResult empty() {
            return new ReturnTrashItemsResult(List.of(), List.of());
        }
    }
}
