package io.github.maaasu.astralRecord.feature.adventurerecord.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.adventurerecord.gui.AdventureRecordGui;
import io.github.maaasu.astralRecord.feature.adventurerecord.model.AdventureRecordListType;
import io.github.maaasu.astralRecord.feature.adventurerecord.service.AdventureRecordService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import io.github.maaasu.astralRecord.shared.gui.paging.PagedGuiView;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 冒険記録 GUI のクリック操作を処理します。
 */
public class AdventureRecordGuiEventHandler extends AbstractEventHandler {
    private final AdventureRecordGui gui;
    private final AdventureRecordService adventureRecordService;
    private final InventoryService inventoryService;
    private final Map<UUID, UUID> listRequestIds = new ConcurrentHashMap<>();

    public AdventureRecordGuiEventHandler(
        @NotNull AdventureRecordGui gui,
        @NotNull AdventureRecordService adventureRecordService,
        @NotNull InventoryService inventoryService
    ) {
        this.gui = gui;
        this.adventureRecordService = adventureRecordService;
        this.inventoryService = inventoryService;
    }

    /**
     * 冒険記録一覧 GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     */
    public void open(@NotNull Player player) {
        setHotbarShortcutMode(player, true);
        openList(player, AdventureRecordListType.ALL, 0, Set.of(), false);
    }

    public boolean isInventory(@Nullable Inventory inventory) {
        return gui.isInventory(inventory);
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
            if (handlePlayerInventoryClick(event, player, topInventory)) {
                return;
            }
            AdventureRecordGui.Screen screen = gui.getScreen(topInventory);
            if (screen == AdventureRecordGui.Screen.MOB_LIST) {
                handleMobListClick(player, topInventory, event.getRawSlot());
                return;
            }
            if (screen == AdventureRecordGui.Screen.MOB_DETAIL) {
                handleMobDetailClick(player, topInventory, event.getRawSlot());
                return;
            }
            if (screen == AdventureRecordGui.Screen.MOB_STATUS_DETAIL) {
                handleMobStatusDetailClick(player, topInventory, event.getRawSlot());
                return;
            }
            if (screen == AdventureRecordGui.Screen.SEARCH) {
                handleSearchClick(player, topInventory, event.getRawSlot());
                return;
            }
            if (screen == AdventureRecordGui.Screen.FILTER) {
                handleFilterClick(player, event.getRawSlot());
            }
        }, LogId.E_5601, event.getWhoClicked().getName(), "adventure_record_gui_click");
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
        }, LogId.E_5601, event.getWhoClicked().getName(), "adventure_record_gui_drag");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(@NotNull InventoryCloseEvent event) {
        runSafely(() -> {
            if (!gui.isInventory(event.getInventory())) {
                return;
            }
            if (event.getPlayer() instanceof Player player) {
                listRequestIds.remove(player.getUniqueId());
                setHotbarShortcutMode(player, false);
            }
        }, LogId.E_5601, event.getPlayer().getName(), "adventure_record_gui_close");
    }

    private void handleMobListClick(@NotNull Player player, @NotNull Inventory inventory, int rawSlot) {
        AdventureRecordListType listType = gui.getListType(inventory);
        if (listType == null) {
            GuiSound.DENY.play(player);
            return;
        }
        if (rawSlot == PagedGuiView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            io.github.maaasu.astralRecord.AstralRecord.getInstance().getGuiNavigationService().openPrevious(player);
            return;
        }
        int pageIndex = gui.getPageIndex(inventory);
        Set<String> searchItemIds = gui.getSearchItemIds(inventory);
        List<AdventureRecordService.Entry> entries = gui.getEntries(inventory);
        boolean superMode = gui.isSuperMode(inventory);
        int itemCount = entries.size();
        if (rawSlot == PagedGuiView.PREVIOUS_SLOT && gui.hasPreviousPage(pageIndex)) {
            GuiSound.PAGE.play(player);
            gui.openMobList(
                player,
                listType,
                entries,
                pageIndex - 1,
                searchItemIds,
                superMode
            );
            return;
        }
        if (rawSlot == PagedGuiView.NEXT_SLOT && gui.hasNextPage(pageIndex, itemCount)) {
            GuiSound.PAGE.play(player);
            gui.openMobList(
                player,
                listType,
                entries,
                pageIndex + 1,
                searchItemIds,
                superMode
            );
            return;
        }
        if (rawSlot == AdventureRecordGui.CATEGORY_FILTER_SLOT && listType != AdventureRecordListType.SEARCH) {
            GuiSound.SELECT.play(player);
            gui.openFilter(player, listType);
            return;
        }
        if (rawSlot == AdventureRecordGui.MOB_SEARCH_SLOT) {
            GuiSound.SELECT.play(player);
            gui.openSearch(player, List.of());
            return;
        }
        AdventureRecordService.Entry entry = gui.getEntryAtSlot(inventory, rawSlot);
        if (entry != null) {
            GuiSound.SELECT.play(player);
            gui.openMobDetail(player, entry);
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void handleMobDetailClick(@NotNull Player player, @NotNull Inventory inventory, int rawSlot) {
        if (rawSlot == PagedGuiView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            io.github.maaasu.astralRecord.AstralRecord.getInstance().getGuiNavigationService().openPrevious(player);
            return;
        }
        AdventureRecordService.Entry entry = gui.getMobEntry(inventory);
        StatusType.Category category = gui.getMobStatusCategoryAtSlot(rawSlot);
        if (entry != null && category != null) {
            GuiSound.SELECT.play(player);
            gui.openMobStatusDetail(player, entry, category, 0);
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void handleMobStatusDetailClick(
        @NotNull Player player,
        @NotNull Inventory inventory,
        int rawSlot
    ) {
        if (rawSlot == PagedGuiView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            io.github.maaasu.astralRecord.AstralRecord.getInstance().getGuiNavigationService().openPrevious(player);
            return;
        }
        AdventureRecordService.Entry entry = gui.getMobEntry(inventory);
        StatusType.Category category = gui.getMobStatusCategory(inventory);
        if (entry == null || category == null) {
            GuiSound.DENY.play(player);
            return;
        }
        int pageIndex = gui.getPageIndex(inventory);
        int itemCount = gui.getMobStatusDetailItemCount(inventory);
        if (rawSlot == PagedGuiView.PREVIOUS_SLOT && gui.hasPreviousPage(pageIndex)) {
            GuiSound.PAGE.play(player);
            gui.openMobStatusDetail(player, entry, category, pageIndex - 1);
            return;
        }
        if (rawSlot == PagedGuiView.NEXT_SLOT && gui.hasNextPage(pageIndex, itemCount)) {
            GuiSound.PAGE.play(player);
            gui.openMobStatusDetail(player, entry, category, pageIndex + 1);
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void handleFilterClick(@NotNull Player player, int rawSlot) {
        AdventureRecordListType selectedType = gui.getFilterTypeAtSlot(rawSlot);
        if (selectedType == null) {
            GuiSound.DENY.play(player);
            return;
        }
        openList(player, selectedType, 0, Set.of());
    }

    private void handleSearchClick(@NotNull Player player, @NotNull Inventory inventory, int rawSlot) {
        if (rawSlot == AdventureRecordGui.SEARCH_BACK_SLOT) {
            GuiSound.SELECT.play(player);
            io.github.maaasu.astralRecord.AstralRecord.getInstance().getGuiNavigationService().openPrevious(player);
            return;
        }
        if (rawSlot == AdventureRecordGui.SEARCH_BUTTON_SLOT) {
            Set<String> ids = gui.getSearchItemIds(inventory);
            openList(player, AdventureRecordListType.SEARCH, 0, ids);
            return;
        }
        if (gui.isSearchItemSlot(rawSlot)) {
            GuiSound.SELECT.play(player);
            gui.openSearch(player, gui.withoutSearchSlot(inventory, rawSlot));
            return;
        }
        GuiSound.DENY.play(player);
    }

    private boolean handlePlayerInventoryClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull Inventory topInventory
    ) {
        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            return false;
        }
        int slot = event.getSlot();
        if (slot >= 0 && slot <= 8 && HotbarShortcutClickSupport.handle(event, player, inventoryService)) {
            return true;
        }
        if (gui.getScreen(topInventory) != AdventureRecordGui.Screen.SEARCH) {
            GuiSound.DENY.play(player);
            return true;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR || ItemStackFactory.getAstralItemId(clicked) == null) {
            GuiSound.DENY.play(player);
            return true;
        }
        List<ItemStack> selected = gui.collectSearchItems(topInventory);
        if (selected.size() >= AdventureRecordGui.SEARCH_ITEM_SLOTS.length) {
            GuiSound.DENY.play(player);
            return true;
        }
        selected.add(clicked.clone());
        GuiSound.SELECT.play(player);
        gui.openSearch(player, selected);
        return true;
    }

    private void openList(
        @NotNull Player player,
        @NotNull AdventureRecordListType listType,
        int pageIndex,
        @NotNull Set<String> searchItemIds
    ) {
        openList(player, listType, pageIndex, searchItemIds, true);
    }

    private void openList(
        @NotNull Player player,
        @NotNull AdventureRecordListType listType,
        int pageIndex,
        @NotNull Set<String> searchItemIds,
        boolean playSelectSound
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        UUID playerId = player.getUniqueId();
        UUID accountId = astPlayer.getAccount().getUuid();
        Inventory sourceInventory = player.getOpenInventory().getTopInventory();
        UUID requestId = UUID.randomUUID();
        listRequestIds.put(playerId, requestId);
        if (playSelectSound) {
            GuiSound.SELECT.play(player);
        }
        adventureRecordService.buildEntriesAsync(
            astPlayer,
            listType,
            searchItemIds,
            result -> {
                if (!listRequestIds.remove(playerId, requestId)) {
                    return;
                }
                Player online = org.bukkit.Bukkit.getPlayer(playerId);
                AstPlayer current = online == null ? null : AstPlayerCache.get(online);
                if (online == null || !online.isOnline() || current == null
                    || !current.getAccount().getUuid().equals(accountId)
                    || online.getOpenInventory().getTopInventory() != sourceInventory) {
                    return;
                }
                gui.openMobList(
                    online,
                    listType,
                    result.entries(),
                    pageIndex,
                    searchItemIds,
                    result.superMode()
                );
            },
            () -> {
                if (listRequestIds.remove(playerId, requestId) && player.isOnline()) {
                    GuiSound.DENY.play(player);
                }
            }
        );
    }

    private void setHotbarShortcutMode(@NotNull Player player, boolean enabled) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null) {
            inventoryService.setHotbarShortcutMode(astPlayer, enabled);
        }
    }
}
