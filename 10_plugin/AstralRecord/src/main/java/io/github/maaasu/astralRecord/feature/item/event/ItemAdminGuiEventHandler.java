package io.github.maaasu.astralRecord.feature.item.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.gui.ItemAdminGuiView;
import io.github.maaasu.astralRecord.feature.item.model.ItemAdminViewOptions;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理者用アイテム一覧 GUI のイベント処理を担当します。
 */
public final class ItemAdminGuiEventHandler extends AbstractEventHandler {
    private static final List<String> RARITY_ORDER = List.of(
        "common",
        "uncommon",
        "rare",
        "epic",
        "legendary",
        "mythic"
    );

    private final ItemAdminGuiView view;
    private final ItemService itemService;
    private final InventoryService inventoryService;
    private final ConcurrentHashMap<UUID, ItemAdminViewOptions> optionsByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> pageByPlayer = new ConcurrentHashMap<>();

    /**
     * 管理者用アイテム一覧 GUI のイベントハンドラを初期化します。
     *
     * @param view GUI 表示 View
     * @param itemService アイテムサービス
     * @param inventoryService インベントリサービス
     */
    public ItemAdminGuiEventHandler(
        @NotNull ItemAdminGuiView view,
        @NotNull ItemService itemService,
        @NotNull InventoryService inventoryService
    ) {
        this.view = view;
        this.itemService = itemService;
        this.inventoryService = inventoryService;
    }

    /**
     * 管理者用アイテム一覧 GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     */
    public void open(@NotNull Player player) {
        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        if (!astPlayer.hasPermissionLevel(UserPermission.ADMIN.getValue())) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5707);
            GuiSound.DENY.play(player);
            return;
        }
        GuiSound.OPEN.play(player);
        open(player, 0);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        runSafely(() -> {
            Inventory topInventory = event.getView().getTopInventory();
            if (!view.isInventory(topInventory)) {
                return;
            }
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            handleClick(event, player, topInventory);
        }, LogId.E_5200, event.getWhoClicked().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        runSafely(() -> {
            if (!view.isInventory(event.getView().getTopInventory())) {
                return;
            }
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                GuiSound.DENY.play(player);
            }
        }, LogId.E_5200, event.getWhoClicked().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(@NotNull InventoryCloseEvent event) {
        if (!view.isInventory(event.getInventory())) {
            return;
        }
        if (event.getPlayer() instanceof Player player) {
            pageByPlayer.remove(player.getUniqueId());
        }
    }

    private void handleClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull Inventory topInventory
    ) {
        int rawSlot = event.getRawSlot();
        if (rawSlot == ItemAdminGuiView.CLOSE_SLOT) {
            GuiSound.CLOSE.play(player);
            player.closeInventory();
            return;
        }
        if (rawSlot >= topInventory.getSize()) {
            GuiSound.DENY.play(player);
            return;
        }

        ItemAdminViewOptions options = options(player);
        List<ItemModel> filteredItems = filteredItems(options);
        int pageIndex = currentPage(player, filteredItems.size());

        if (rawSlot == ItemAdminGuiView.PREVIOUS_SLOT) {
            if (!view.hasPreviousPage(pageIndex)) {
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.SELECT.play(player);
            rerender(player, topInventory, pageIndex - 1);
            return;
        }
        if (rawSlot == ItemAdminGuiView.NEXT_SLOT) {
            if (!view.hasNextPage(pageIndex, filteredItems.size())) {
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.SELECT.play(player);
            rerender(player, topInventory, pageIndex + 1);
            return;
        }
        if (rawSlot == ItemAdminGuiView.CATEGORY_FILTER_SLOT) {
            optionsByPlayer.put(player.getUniqueId(), options.withCategoryFilter(nextFilterValue(options.categoryFilter(), availableCategories())));
            GuiSound.SELECT.play(player);
            rerender(player, topInventory, 0);
            return;
        }
        if (rawSlot == ItemAdminGuiView.RARITY_FILTER_SLOT) {
            optionsByPlayer.put(player.getUniqueId(), options.withRarityFilter(nextFilterValue(options.rarityFilter(), availableRarities())));
            GuiSound.SELECT.play(player);
            rerender(player, topInventory, 0);
            return;
        }
        if (rawSlot == ItemAdminGuiView.GUIDE_SLOT) {
            GuiSound.DENY.play(player);
            return;
        }
        if (rawSlot < 0 || rawSlot >= ItemAdminGuiView.CONTENT_SLOT_COUNT) {
            GuiSound.DENY.play(player);
            return;
        }

        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !astPlayer.hasPermissionLevel(UserPermission.ADMIN.getValue())) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5707);
            GuiSound.DENY.play(player);
            return;
        }

        var current = topInventory.getItem(rawSlot);
        if (current == null || view.isContentPlaceholder(current)) {
            GuiSound.DENY.play(player);
            return;
        }
        String itemId = ItemStackFactory.getAstralItemId(current);
        if (itemId == null || itemId.isBlank()) {
            GuiSound.DENY.play(player);
            return;
        }
        ItemModel model = itemService.findLoadedById(itemId);
        if (model == null) {
            GuiSound.DENY.play(player);
            return;
        }
        int requestedAmount = resolveGrantAmount(event.getClick(), Math.clamp(model.getMaxStack(), 1, 64));
        if (requestedAmount <= 0) {
            GuiSound.DENY.play(player);
            return;
        }

        int granted = inventoryService.addItemToNormalInventory(astPlayer, model, requestedAmount, "admin_gui");
        if (granted <= 0) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5241);
            GuiSound.DENY.play(player);
            player.updateInventory();
            return;
        }
        GuiSound.SELECT.play(player);
        player.updateInventory();
    }

    private void open(@NotNull Player player, int requestedPage) {
        ItemAdminViewOptions options = options(player);
        List<ItemModel> filteredItems = filteredItems(options);
        int normalizedPage = view.normalizePage(requestedPage, filteredItems.size());
        pageByPlayer.put(player.getUniqueId(), normalizedPage);
        view.open(player, filteredItems, options, normalizedPage);
    }

    private void rerender(@NotNull Player player, @NotNull Inventory topInventory, int requestedPage) {
        ItemAdminViewOptions options = options(player);
        List<ItemModel> filteredItems = filteredItems(options);
        int normalizedPage = view.normalizePage(requestedPage, filteredItems.size());
        pageByPlayer.put(player.getUniqueId(), normalizedPage);
        view.render(topInventory, filteredItems, options, normalizedPage);
    }

    private @NotNull ItemAdminViewOptions options(@NotNull Player player) {
        return optionsByPlayer.computeIfAbsent(player.getUniqueId(), ignored -> ItemAdminViewOptions.defaults());
    }

    private int currentPage(@NotNull Player player, int itemCount) {
        return view.normalizePage(pageByPlayer.getOrDefault(player.getUniqueId(), 0), itemCount);
    }

    private @NotNull List<ItemModel> filteredItems(@NotNull ItemAdminViewOptions options) {
        return itemService.getLoadedItems().stream()
            .filter(item -> matchesFilter(item.getCategory(), options.categoryFilter()))
            .filter(item -> matchesFilter(item.getRarity(), options.rarityFilter()))
            .toList();
    }

    private boolean matchesFilter(@Nullable String actualValue, @Nullable String filterValue) {
        if (filterValue == null || filterValue.isBlank()) {
            return true;
        }
        return actualValue != null && actualValue.equalsIgnoreCase(filterValue);
    }

    private @NotNull List<String> availableCategories() {
        return itemService.getLoadedItems().stream()
            .map(ItemModel::getCategory)
            .filter(category -> category != null && !category.isBlank())
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    private @NotNull List<String> availableRarities() {
        List<String> loaded = itemService.getLoadedItems().stream()
            .map(ItemModel::getRarity)
            .filter(rarity -> rarity != null && !rarity.isBlank())
            .map(rarity -> rarity.toLowerCase(Locale.ROOT))
            .distinct()
            .toList();

        List<String> ordered = new ArrayList<>();
        for (String rarity : RARITY_ORDER) {
            if (loaded.contains(rarity)) {
                ordered.add(rarity);
            }
        }
        loaded.stream()
            .filter(rarity -> !ordered.contains(rarity))
            .sorted(Comparator.naturalOrder())
            .forEach(ordered::add);
        return ordered;
    }

    private @Nullable String nextFilterValue(@Nullable String current, @NotNull List<String> values) {
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
        return values.get(0);
    }

    private int resolveGrantAmount(@NotNull ClickType clickType, int stackAmount) {
        if (stackAmount <= 0) {
            return 0;
        }
        return switch (clickType) {
            case LEFT -> 1;
            case RIGHT -> Math.max(1, (stackAmount + 1) / 2);
            case SHIFT_LEFT -> stackAmount;
            default -> 0;
        };
    }
}
