package io.github.maaasu.astralRecord.feature.storage.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.service.ItemTransferSupport;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.service.MenuGuiTransitionService;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.storage.model.StorageViewEntry;
import io.github.maaasu.astralRecord.feature.storage.model.StorageViewOptions;
import io.github.maaasu.astralRecord.feature.storage.view.StorageScreenView;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ストレージ GUI の状態管理とイベント処理を担当するサービスです。
 */
public final class StorageService {
    private final MenuView menuView;
    private final InventoryService inventoryService;
    private final MenuGuiTransitionService menuGuiTransitionService;
    private final ConcurrentHashMap<UUID, StorageViewOptions> storageOptionsByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<StorageViewEntry>> storageEntriesByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> storagePageByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> dirtyStorageByPlayer = new ConcurrentHashMap<>();

    /**
     * ストレージ GUI サービスを初期化します。
     *
     * @param menuView メニュー GUI 表示
     * @param inventoryService インベントリサービス
     * @param menuGuiTransitionService GUI 切替サービス
     */
    public StorageService(
        @NotNull MenuView menuView,
        @NotNull InventoryService inventoryService,
        @NotNull MenuGuiTransitionService menuGuiTransitionService
    ) {
        this.menuView = menuView;
        this.inventoryService = inventoryService;
        this.menuGuiTransitionService = menuGuiTransitionService;
    }

    /**
     * ストレージ GUI の先頭ページを開きます。
     *
     * @param player 対象プレイヤー
     */
    public void open(@NotNull Player player) {
        open(player, 0);
    }

    /**
     * ストレージ GUI の指定ページを開きます。
     *
     * @param player 対象プレイヤー
     * @param pageIndex 表示ページ
     */
    public void open(@NotNull Player player, int pageIndex) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            GuiSound.DENY.play(player);
            return;
        }
        StorageViewOptions options = storageOptions(player);
        List<StorageViewEntry> entries = refreshStorageEntries(player, options);
        int normalizedPage = normalizeStoragePage(pageIndex, entries.size());
        storagePageByPlayer.put(player.getUniqueId(), normalizedPage);
        menuGuiTransitionService.switchGuiWithInventoryRestore(
            player,
            () -> menuView.openStorage(player, entries, options, normalizedPage)
        );
    }

    /**
     * ストレージ GUI のクリックイベントを処理します。
     *
     * @param event クリックイベント
     */
    public void handleClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (menuView.getMenuScreen(event.getView().getTopInventory()) != MenuScreen.STORAGE) {
            return;
        }
        event.setCancelled(true);
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            player.closeInventory();
            return;
        }
        int rawSlot = event.getRawSlot();
        Inventory topInventory = event.getView().getTopInventory();

        if (rawSlot >= topInventory.getSize()) {
            handleStoragePlayerInventoryClick(event, player, topInventory);
            return;
        }

        int pageIndex = storagePageByPlayer.getOrDefault(player.getUniqueId(), menuView.getPageIndex(topInventory));
        var options = storageOptions(player);
        List<StorageViewEntry> entries = currentStorageEntries(player, options);
        if (rawSlot == MenuView.STORAGE_PREVIOUS_SLOT) {
            if (menuView.hasPreviousStoragePage(pageIndex)) {
                GuiSound.SELECT.play(player);
                rerenderStorageInventory(player, topInventory, pageIndex - 1);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (rawSlot == MenuView.STORAGE_NEXT_SLOT) {
            if (menuView.hasNextStoragePage(entries, pageIndex)) {
                GuiSound.SELECT.play(player);
                rerenderStorageInventory(player, topInventory, pageIndex + 1);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (rawSlot == MenuView.STORAGE_CATEGORY_FILTER_SLOT) {
            GuiSound.SELECT.play(player);
            storageOptionsByPlayer.put(player.getUniqueId(), options.withCategoryFilter(nextStorageCategory(options.categoryFilter())));
            refreshStorageEntries(player, storageOptions(player));
            rerenderStorageInventory(player, topInventory, 0);
            return;
        }
        if (rawSlot == MenuView.STORAGE_RARITY_FILTER_SLOT) {
            GuiSound.SELECT.play(player);
            storageOptionsByPlayer.put(player.getUniqueId(), options.withRarityFilter(nextStorageRarity(options.rarityFilter())));
            refreshStorageEntries(player, storageOptions(player));
            rerenderStorageInventory(player, topInventory, 0);
            return;
        }
        if (rawSlot == MenuView.STORAGE_SORT_KEY_SLOT) {
            GuiSound.SELECT.play(player);
            storageOptionsByPlayer.put(player.getUniqueId(), options.withSortKey(options.sortKey().next()));
            refreshStorageEntries(player, storageOptions(player));
            rerenderStorageInventory(player, topInventory, 0);
            return;
        }
        if (rawSlot == MenuView.STORAGE_SORT_DIRECTION_SLOT) {
            GuiSound.SELECT.play(player);
            storageOptionsByPlayer.put(player.getUniqueId(), options.withSortDirection(options.sortDirection().next()));
            refreshStorageEntries(player, storageOptions(player));
            rerenderStorageInventory(player, topInventory, 0);
            return;
        }
        if (isStorageControlSlot(rawSlot)) {
            GuiSound.DENY.play(player);
            return;
        }
        if (!isStorageContentSlot(rawSlot)) {
            GuiSound.DENY.play(player);
            return;
        }
        handleStorageContentClick(event, player, topInventory, rawSlot);
    }

    /**
     * ストレージ GUI のドラッグイベントを処理します。
     *
     * @param event ドラッグイベント
     */
    public void handleDrag(@NotNull InventoryDragEvent event) {
        if (menuView.getMenuScreen(event.getView().getTopInventory()) != MenuScreen.STORAGE) {
            return;
        }
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player) {
            if (!AccountModeGuard.isGameplayPlayer(player)) {
                player.closeInventory();
                return;
            }
            GuiSound.DENY.play(player);
        }
    }

    /**
     * ストレージ GUI を閉じたときに、開いている間の変更をまとめて保存します。
     *
     * @param event Bukkit のインベントリクローズイベント
     */
    public void handleClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (menuView.getMenuScreen(event.getInventory()) != MenuScreen.STORAGE) {
            return;
        }
        storageEntriesByPlayer.remove(player.getUniqueId());
        storagePageByPlayer.remove(player.getUniqueId());
        if (dirtyStorageByPlayer.remove(player.getUniqueId()) == null) {
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null) {
            inventoryService.saveNow(astPlayer.getAccount().getUuid());
        }
    }

    private @NotNull StorageViewOptions storageOptions(@NotNull Player player) {
        return storageOptionsByPlayer.computeIfAbsent(player.getUniqueId(), ignored -> StorageViewOptions.defaults());
    }

    private @NotNull List<StorageViewEntry> currentStorageEntries(
        @NotNull Player player,
        @NotNull StorageViewOptions options
    ) {
        List<StorageViewEntry> entries = storageEntriesByPlayer.get(player.getUniqueId());
        return entries == null ? refreshStorageEntries(player, options) : entries;
    }

    private @NotNull List<StorageViewEntry> refreshStorageEntries(
        @NotNull Player player,
        @NotNull StorageViewOptions options
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            storageEntriesByPlayer.remove(player.getUniqueId());
            return List.of();
        }
        List<StorageViewEntry> entries = inventoryService.getStorageViewEntries(astPlayer.getAccount().getUuid(), options);
        storageEntriesByPlayer.put(player.getUniqueId(), entries);
        return entries;
    }

    private void handleStoragePlayerInventoryClick(
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
        int moved = inventoryService.moveOwnedItemToStorage(astPlayer, event.getSlot(), requested);
        if (moved <= 0) {
            GuiSound.DENY.play(player);
            player.updateInventory();
            return;
        }
        dirtyStorageByPlayer.put(player.getUniqueId(), true);
        refreshStorageEntries(player, storageOptions(player));
        GuiSound.SELECT.play(player);
        rerenderStorageInventory(player, topInventory);
        player.updateInventory();
    }

    private void handleStorageContentClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull Inventory topInventory,
        int rawSlot
    ) {
        ItemStack current = topInventory.getItem(rawSlot);
        if (current == null || current.getType() == Material.AIR || menuView.isStorageContentPlaceholder(current)) {
            GuiSound.DENY.play(player);
            return;
        }
        UUID storageEntryId = menuView.getStorageEntryId(current);
        if (storageEntryId == null) {
            GuiSound.DENY.play(player);
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            GuiSound.DENY.play(player);
            return;
        }
        int sourceAmount = currentStorageEntries(player, storageOptions(player)).stream()
            .filter(entry -> entry.entry().getInventoryEntryId().equals(storageEntryId))
            .findFirst()
            .map(entry -> (int) Math.clamp(entry.entry().getQuantity(), 0L, Integer.MAX_VALUE))
            .orElse(current.getAmount());
        int requested = ItemTransferSupport.resolveTransferAmount(event.getClick(), sourceAmount);
        if (requested <= 0) {
            GuiSound.DENY.play(player);
            return;
        }
        int moved = inventoryService.withdrawStorageEntry(astPlayer, storageEntryId, requested);
        if (moved <= 0) {
            GuiSound.DENY.play(player);
            player.updateInventory();
            return;
        }
        dirtyStorageByPlayer.put(player.getUniqueId(), true);
        refreshStorageEntries(player, storageOptions(player));
        GuiSound.SELECT.play(player);
        rerenderStorageInventory(player, topInventory);
        player.updateInventory();
    }

    private void rerenderStorageInventory(@NotNull Player player, @NotNull Inventory topInventory) {
        rerenderStorageInventory(
            player,
            topInventory,
            storagePageByPlayer.getOrDefault(player.getUniqueId(), menuView.getPageIndex(topInventory))
        );
    }

    private void rerenderStorageInventory(@NotNull Player player, @NotNull Inventory topInventory, int pageIndex) {
        StorageViewOptions options = storageOptions(player);
        List<StorageViewEntry> entries = currentStorageEntries(player, options);
        int normalizedPage = normalizeStoragePage(pageIndex, entries.size());
        storagePageByPlayer.put(player.getUniqueId(), normalizedPage);
        menuView.renderStorage(topInventory, entries, options, normalizedPage);
    }

    private int normalizeStoragePage(int pageIndex, int itemCount) {
        return GuiPagination.normalizePage(pageIndex, itemCount, StorageScreenView.CONTENT_SLOT_COUNT);
    }

    private boolean isStorageContentSlot(int rawSlot) {
        return ItemTransferSupport.isContentSlot(rawSlot, StorageScreenView.CONTENT_SLOT_COUNT);
    }

    private boolean isStorageControlSlot(int rawSlot) {
        return rawSlot == MenuView.STORAGE_PREVIOUS_SLOT
            || rawSlot == MenuView.STORAGE_CATEGORY_FILTER_SLOT
            || rawSlot == MenuView.STORAGE_RARITY_FILTER_SLOT
            || rawSlot == MenuView.STORAGE_SORT_KEY_SLOT
            || rawSlot == MenuView.STORAGE_SORT_DIRECTION_SLOT
            || rawSlot == MenuView.STORAGE_GUIDE_SLOT
            || rawSlot == MenuView.STORAGE_NEXT_SLOT;
    }

    private @Nullable String nextStorageCategory(@Nullable String current) {
        List<String> values = ItemCategory.supportedApiValues();
        return nextNullableCycle(current, values);
    }

    private @Nullable String nextStorageRarity(@Nullable String current) {
        return nextNullableCycle(current, List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"));
    }

    private @Nullable String nextNullableCycle(@Nullable String current, @NotNull List<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        if (current == null || current.isBlank()) {
            return values.get(0);
        }
        for (int index = 0; index < values.size(); index++) {
            if (!values.get(index).equalsIgnoreCase(current)) {
                continue;
            }
            int nextIndex = index + 1;
            return nextIndex >= values.size() ? null : values.get(nextIndex);
        }
        return null;
    }
}
