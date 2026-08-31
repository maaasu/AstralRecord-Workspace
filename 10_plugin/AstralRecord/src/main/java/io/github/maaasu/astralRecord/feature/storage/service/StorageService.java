package io.github.maaasu.astralRecord.feature.storage.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemRarity;
import io.github.maaasu.astralRecord.feature.item.service.ItemTransferSupport;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.service.MenuGuiTransitionService;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.storage.model.StorageViewEntry;
import io.github.maaasu.astralRecord.feature.storage.model.StorageViewOptions;
import io.github.maaasu.astralRecord.feature.storage.model.StorageSortDirection;
import io.github.maaasu.astralRecord.feature.storage.model.StorageSortKey;
import io.github.maaasu.astralRecord.feature.storage.view.StorageScreenView;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ストレージ GUI の状態管理とイベント処理を担当するサービスです。
 */
public final class StorageService {
    private final MenuView menuView;
    private final InventoryService inventoryService;
    private final InventorySaveCoordinator inventorySaveCoordinator;
    private final MenuGuiTransitionService menuGuiTransitionService;
    private final PlayerMessageService playerMessageService;
    private final ConcurrentHashMap<UUID, StorageViewOptions> storageOptionsByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<StorageViewEntry>> storageEntriesByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> storagePageByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> dirtyStorageVersionByPlayer = new ConcurrentHashMap<>();

    /**
     * ストレージ GUI サービスを初期化します。
     *
     * @param menuView メニュー GUI 表示
     * @param inventoryService インベントリサービス
     * @param inventorySaveCoordinator インベントリ保存コーディネーター
     * @param menuGuiTransitionService GUI 切替サービス
     * @param playerMessageService プレイヤーメッセージサービス
     */
    public StorageService(
        @NotNull MenuView menuView,
        @NotNull InventoryService inventoryService,
        @NotNull InventorySaveCoordinator inventorySaveCoordinator,
        @NotNull MenuGuiTransitionService menuGuiTransitionService,
        @NotNull PlayerMessageService playerMessageService
    ) {
        this.menuView = menuView;
        this.inventoryService = inventoryService;
        this.inventorySaveCoordinator = inventorySaveCoordinator;
        this.menuGuiTransitionService = menuGuiTransitionService;
        this.playerMessageService = playerMessageService;
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
        StorageScreenView.FilterType filterType = resolveFilterType(menuView.getContentId(event.getView().getTopInventory()));
        if (filterType != null) {
            handleFilterOptionClick(event, player, filterType);
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
                GuiSound.PAGE.play(player);
                rerenderStorageInventory(player, topInventory, pageIndex - 1);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (rawSlot == MenuView.STORAGE_NEXT_SLOT) {
            if (menuView.hasNextStoragePage(entries, pageIndex)) {
                GuiSound.PAGE.play(player);
                rerenderStorageInventory(player, topInventory, pageIndex + 1);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (rawSlot == MenuView.STORAGE_CATEGORY_FILTER_SLOT) {
            GuiSound.SELECT.play(player);
            openFilterSelection(player, StorageScreenView.FilterType.CATEGORY, options.categoryFilter());
            return;
        }
        if (rawSlot == MenuView.STORAGE_RARITY_FILTER_SLOT) {
            GuiSound.SELECT.play(player);
            openFilterSelection(player, StorageScreenView.FilterType.RARITY, options.rarityFilter());
            return;
        }
        if (rawSlot == MenuView.STORAGE_SORT_KEY_SLOT) {
            GuiSound.SELECT.play(player);
            openFilterSelection(player, StorageScreenView.FilterType.SORT_KEY, options.sortKey().name());
            return;
        }
        if (rawSlot == MenuView.STORAGE_SORT_DIRECTION_SLOT) {
            GuiSound.SELECT.play(player);
            openFilterSelection(player, StorageScreenView.FilterType.SORT_DIRECTION, options.sortDirection().name());
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
     * @param player GUI セッションを終了したプレイヤー
     * @param inventory 終了したストレージ inventory
     */
    public void handleClose(@NotNull Player player, @NotNull Inventory inventory) {
        if (menuView.getMenuScreen(inventory) != MenuScreen.STORAGE) {
            return;
        }
        if (resolveFilterType(menuView.getContentId(inventory)) != null) {
            return;
        }
        storageEntriesByPlayer.remove(player.getUniqueId());
        storagePageByPlayer.remove(player.getUniqueId());
        UUID playerId = player.getUniqueId();
        Long requestedVersion = dirtyStorageVersionByPlayer.get(playerId);
        if (requestedVersion == null) {
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null) {
            UUID accountId = astPlayer.getAccount().getUuid();
            inventorySaveCoordinator.saveNow(accountId).whenComplete((succeeded, throwable) -> {
                // 永続化失敗時の再試行要否は PlayerInventoryState の dirty が保持する。
                dirtyStorageVersionByPlayer.computeIfPresent(
                    playerId,
                    (ignored, currentVersion) -> currentVersion.equals(requestedVersion) ? null : currentVersion
                );
            });
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
        int moved;
        if (ItemTransferSupport.isAllStacksTransfer(event.getClick())) {
            moved = inventoryService.moveAllOwnedMatchingItemsToStorage(astPlayer, event.getSlot());
        } else {
            int requested = ItemTransferSupport.resolveTransferAmount(
                event.getClick(),
                clicked.getAmount(),
                clicked.getMaxStackSize()
            );
            if (requested <= 0) {
                GuiSound.DENY.play(player);
                return;
            }
            moved = inventoryService.moveOwnedItemToStorage(astPlayer, event.getSlot(), requested);
        }
        if (moved <= 0) {
            GuiSound.DENY.play(player);
            player.updateInventory();
            return;
        }
        markStorageDirty(player.getUniqueId());
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
        StorageViewEntry sourceEntry = currentStorageEntries(player, storageOptions(player)).stream()
            .filter(entry -> entry.entry().getInventoryEntryId().equals(storageEntryId))
            .findFirst()
            .orElse(null);
        int sourceAmount = sourceEntry == null
            ? current.getAmount()
            : (int) Math.clamp(sourceEntry.entry().getQuantity(), 0L, Integer.MAX_VALUE);
        int maxStackSize = sourceEntry == null
            ? current.getMaxStackSize()
            : Math.max(1, sourceEntry.itemModel().getMaxStack());
        int requested = ItemTransferSupport.resolveTransferAmount(
            event.getClick(),
            sourceAmount,
            maxStackSize
        );
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
        if (sourceEntry != null) {
            playerMessageService.send(
                player,
                PlayerMsgId.P_5253,
                sourceEntry.itemModel().getName(),
                moved
            );
        }
        markStorageDirty(player.getUniqueId());
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

    private void openFilterSelection(
        @NotNull Player player,
        @NotNull StorageScreenView.FilterType filterType,
        @Nullable String selectedValue
    ) {
        menuGuiTransitionService.switchGuiWithoutInventoryReload(
            player,
            () -> menuView.openStorageFilter(player, filterType, selectedValue)
        );
    }

    private void handleFilterOptionClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull StorageScreenView.FilterType filterType
    ) {
        int rawSlot = event.getRawSlot();
        List<String> values = filterValues(filterType);
        if (rawSlot < 0 || rawSlot >= values.size()) {
            GuiSound.DENY.play(player);
            return;
        }
        String value = values.get(rawSlot);
        StorageViewOptions options = storageOptions(player);
        StorageViewOptions updated = switch (filterType) {
            case CATEGORY -> options.withCategoryFilter(value);
            case RARITY -> options.withRarityFilter(value);
            case SORT_KEY -> options.withSortKey(StorageSortKey.valueOf(value));
            case SORT_DIRECTION -> options.withSortDirection(StorageSortDirection.valueOf(value));
        };
        storageOptionsByPlayer.put(player.getUniqueId(), updated);
        List<StorageViewEntry> entries = refreshStorageEntries(player, updated);
        GuiSound.SELECT.play(player);
        menuGuiTransitionService.switchGuiWithoutInventoryReload(
            player,
            () -> menuView.openStorage(player, entries, updated, 0)
        );
    }

    private @NotNull List<String> filterValues(@NotNull StorageScreenView.FilterType filterType) {
        return switch (filterType) {
            case CATEGORY -> {
                List<String> values = new java.util.ArrayList<>();
                values.add(null);
                values.addAll(ItemCategory.supportedApiValues());
                yield values;
            }
            case RARITY -> {
                List<String> values = new java.util.ArrayList<>();
                values.add(null);
                values.addAll(ItemRarity.orderedValues());
                yield values;
            }
            case SORT_KEY -> java.util.Arrays.stream(StorageSortKey.values()).map(Enum::name).toList();
            case SORT_DIRECTION -> java.util.Arrays.stream(StorageSortDirection.values()).map(Enum::name).toList();
        };
    }

    private @Nullable StorageScreenView.FilterType resolveFilterType(@Nullable String contentId) {
        if (contentId == null || !contentId.startsWith("filter:")) {
            return null;
        }
        try {
            return StorageScreenView.FilterType.valueOf(contentId.substring("filter:".length()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void markStorageDirty(@NotNull UUID playerId) {
        dirtyStorageVersionByPlayer.merge(playerId, 1L, Long::sum);
    }

}
