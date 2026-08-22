package io.github.maaasu.astralRecord.feature.adventurerecord.gui;

import io.github.maaasu.astralRecord.feature.adventurerecord.model.AdventureRecordListType;
import io.github.maaasu.astralRecord.feature.adventurerecord.service.AdventureRecordService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropItem;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.display.DisplaySeparators;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import io.github.maaasu.astralRecord.shared.gui.paging.PagedGuiView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 冒険記録 GUI の描画を担当します。
 */
public class AdventureRecordGui {
    public static final int SIZE = 54;
    public static final int CATEGORY_FILTER_SLOT = 46;
    public static final int MOB_SEARCH_SLOT = 47;
    public static final int SEARCH_BUTTON_SLOT = 53;
    public static final int SEARCH_BACK_SLOT = BaseMenuScreenView.BACK_SLOT;
    public static final int[] SEARCH_ITEM_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
        .withZone(ZoneId.systemDefault());

    private final PagedGuiView pagedGuiView = new PagedGuiView();
    private final ItemService itemService;

    public AdventureRecordGui(@NotNull ItemService itemService) {
        this.itemService = itemService;
    }

    /**
     * Mob 記録一覧 GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     * @param listType 一覧種別
     * @param entries 表示エントリ
     * @param pageIndex 0 始まりページ
     * @param searchItemIds 検索に使用した item ID
     * @param superMode スーパーモードが有効か
     */
    public void openMobList(
        @NotNull Player player,
        @NotNull AdventureRecordListType listType,
        @NotNull List<AdventureRecordService.Entry> entries,
        int pageIndex,
        @NotNull Set<String> searchItemIds,
        boolean superMode
    ) {
        List<ItemStack> icons = entries.stream()
            .map(entry -> createMobItem(entry, superMode))
            .toList();
        int normalizedPage = pagedGuiView.normalizePage(pageIndex, icons.size());
        int totalPages = pagedGuiView.totalPages(icons.size());
        Inventory inventory = Bukkit.createInventory(
            new Holder(
                Screen.MOB_LIST,
                listType,
                normalizedPage,
                Set.copyOf(searchItemIds),
                List.copyOf(entries),
                superMode
            ),
            SIZE,
            Component.text(listType.getTitle() + " " + (normalizedPage + 1) + "/" + totalPages, NamedTextColor.GOLD)
        );
        pagedGuiView.render(inventory, icons, normalizedPage);
        renderListControls(inventory, listType);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * Mob 記録のカテゴリフィルター候補 GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     * @param selectedType 現在選択中の一覧種別
     */
    public void openFilter(@NotNull Player player, @NotNull AdventureRecordListType selectedType) {
        Inventory inventory = Bukkit.createInventory(
            new Holder(Screen.FILTER, selectedType, 0, Set.of(), List.of(), false),
            SIZE,
            Component.text("冒険記録のカテゴリ", NamedTextColor.AQUA)
        );
        fill(inventory);
        List<AdventureRecordListType> options = filterOptions();
        for (int index = 0; index < options.size(); index++) {
            AdventureRecordListType option = options.get(index);
            boolean selected = option == selectedType;
            NamedTextColor color = optionColor(option);
            List<Component> lore = selected
                ? List.of(Component.text("現在選択中", NamedTextColor.GREEN))
                : List.of(Component.text("クリックで適用", NamedTextColor.GRAY));
            inventory.setItem(index, createItem(optionMaterial(option), Component.text(
                optionFilterLabel(option),
                color
            ), lore));
        }
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * Mob 検索条件 GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     * @param selectedItems 検索対象アイテム
     */
    public void openSearch(@NotNull Player player, @NotNull List<ItemStack> selectedItems) {
        Inventory inventory = Bukkit.createInventory(
            new Holder(Screen.SEARCH, null, 0, selectedItemIds(selectedItems), List.of(), false),
            SIZE,
            Component.text("アイテムを指定してモブを検索", NamedTextColor.AQUA)
        );
        fill(inventory);
        for (int i = 0; i < SEARCH_ITEM_SLOTS.length; i++) {
            ItemStack item = i < selectedItems.size() ? selectedItems.get(i).clone() : searchPlaceholder();
            item.setAmount(1);
            inventory.setItem(SEARCH_ITEM_SLOTS[i], item);
        }
        inventory.setItem(SEARCH_BACK_SLOT, backItem());
        inventory.setItem(SEARCH_BUTTON_SLOT, createItem(
            Material.LIME_DYE,
            Component.text("検索", NamedTextColor.GREEN),
            List.of(Component.text("指定アイテムをドロップするモブを表示", NamedTextColor.GRAY))
        ));
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    public @Nullable Screen getScreen(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.screen();
        }
        return null;
    }

    public @Nullable AdventureRecordListType getListType(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.listType();
        }
        return null;
    }

    public int getPageIndex(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.pageIndex();
        }
        return 0;
    }

    public @NotNull Set<String> getSearchItemIds(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.searchItemIds();
        }
        return Set.of();
    }

    public @NotNull List<AdventureRecordService.Entry> getEntries(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.entries();
        }
        return List.of();
    }

    /**
     * フィルター候補 GUI のクリック位置から一覧種別を解決します。
     *
     * @param rawSlot クリックされた raw slot
     * @return 対応する一覧種別。候補外の場合は null
     */
    public @Nullable AdventureRecordListType getFilterTypeAtSlot(int rawSlot) {
        List<AdventureRecordListType> options = filterOptions();
        return rawSlot >= 0 && rawSlot < options.size() ? options.get(rawSlot) : null;
    }

    public boolean isSuperMode(@Nullable Inventory inventory) {
        return inventory != null
            && inventory.getHolder() instanceof Holder holder
            && holder.superMode();
    }

    public boolean isSearchItemSlot(int rawSlot) {
        for (int slot : SEARCH_ITEM_SLOTS) {
            if (slot == rawSlot) {
                return true;
            }
        }
        return false;
    }

    public boolean hasPreviousPage(int pageIndex) {
        return pagedGuiView.hasPreviousPage(pageIndex);
    }

    public boolean hasNextPage(int pageIndex, int itemCount) {
        return pagedGuiView.hasNextPage(pageIndex, itemCount);
    }

    public @NotNull List<ItemStack> collectSearchItems(@NotNull Inventory inventory) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot : SEARCH_ITEM_SLOTS) {
            ItemStack item = inventory.getItem(slot);
            if (isEmptySearchItem(item)) {
                continue;
            }
            ItemStack normalized = item.clone();
            normalized.setAmount(1);
            result.add(normalized);
        }
        return result;
    }

    public @NotNull List<ItemStack> withoutSearchSlot(@NotNull Inventory inventory, int rawSlot) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot : SEARCH_ITEM_SLOTS) {
            if (slot == rawSlot) {
                continue;
            }
            ItemStack item = inventory.getItem(slot);
            if (!isEmptySearchItem(item)) {
                ItemStack normalized = item.clone();
                normalized.setAmount(1);
                result.add(normalized);
            }
        }
        return result;
    }

    private @NotNull ItemStack createMobItem(@NotNull AdventureRecordService.Entry entry, boolean superMode) {
        MobTemplate template = entry.template();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("種類: " + categoryLabel(template.category()), NamedTextColor.AQUA));
        lore.add(Component.text("レベル: " + template.level(), NamedTextColor.YELLOW));
        if (superMode) {
            lore.add(Component.text("管理ID: " + template.id(), NamedTextColor.DARK_GRAY));
            lore.add(Component.text(entry.defeated() ? "討伐済み" : "未討伐", entry.defeated() ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
        long defeatCount = entry.record() == null ? 0 : entry.record().defeatCount();
        lore.add(Component.text("討伐数: " + defeatCount, NamedTextColor.GOLD));
        if (entry.record() != null) {
            lore.add(Component.text("最新討伐: " + DATE_FORMATTER.format(entry.record().lastDefeatedAt()), NamedTextColor.GRAY));
        }
        if (!template.lore().isEmpty()) {
            lore.add(Component.text("説明", NamedTextColor.AQUA));
            template.lore().stream().limit(4)
                .map(line -> ColorCodeUtil.toPlainText(line, line))
                .forEach(line -> lore.add(Component.text("- " + line, NamedTextColor.WHITE)));
            lore.add(Component.text(DisplaySeparators.SECTION, NamedTextColor.DARK_GRAY));
        }
        appendDrops(lore, template.drops());
        return createItem(resolveMaterial(template.icon(), Material.ZOMBIE_HEAD), Component.text(
            ColorCodeUtil.toPlainText(template.displayName(), template.id()),
            NamedTextColor.WHITE
        ), lore);
    }

    private void renderListControls(
        @NotNull Inventory inventory,
        @NotNull AdventureRecordListType listType
    ) {
        if (listType != AdventureRecordListType.SEARCH) {
            inventory.setItem(CATEGORY_FILTER_SLOT, createItem(
                Material.HOPPER,
                Component.text("カテゴリフィルター", NamedTextColor.AQUA),
                List.of(
                    Component.text("現在: " + optionFilterLabel(listType), NamedTextColor.WHITE),
                    Component.text("クリックで候補一覧を表示", NamedTextColor.GRAY)
                )
            ));
        }
        inventory.setItem(MOB_SEARCH_SLOT, createItem(
            Material.COMPASS,
            Component.text("モブ検索", NamedTextColor.AQUA),
            List.of(Component.text("ドロップアイテムから全モブを検索", NamedTextColor.GRAY))
        ));
    }

    private @NotNull List<AdventureRecordListType> filterOptions() {
        return List.of(
            AdventureRecordListType.ALL,
            AdventureRecordListType.ENEMY,
            AdventureRecordListType.BOSS
        );
    }

    private @NotNull String optionFilterLabel(@NotNull AdventureRecordListType listType) {
        return switch (listType) {
            case ALL -> "すべて";
            case ENEMY -> "エネミーのみ";
            case BOSS -> "ボスのみ";
            case SEARCH -> "検索結果";
        };
    }

    private @NotNull NamedTextColor optionColor(@NotNull AdventureRecordListType listType) {
        return switch (listType) {
            case ALL -> NamedTextColor.WHITE;
            case ENEMY -> NamedTextColor.GREEN;
            case BOSS -> NamedTextColor.RED;
            case SEARCH -> NamedTextColor.AQUA;
        };
    }

    private @NotNull Material optionMaterial(@NotNull AdventureRecordListType listType) {
        return switch (listType) {
            case ALL -> Material.BARRIER;
            case ENEMY -> Material.ZOMBIE_HEAD;
            case BOSS -> Material.DRAGON_HEAD;
            case SEARCH -> Material.COMPASS;
        };
    }

    private @NotNull String categoryLabel(@NotNull MobCategory category) {
        return switch (category) {
            case ENEMY -> "エネミー";
            case BOSS -> "ボス";
            case NPC -> "NPC";
        };
    }

    private void appendDrops(@NotNull List<Component> lore, @Nullable MobDropConfig drops) {
        if (drops == null) {
            lore.add(Component.text("ドロップ: なし", NamedTextColor.DARK_GRAY));
            return;
        }
        lore.add(Component.text("ドロップ", NamedTextColor.AQUA));
        if (drops.exp() > 0) {
            lore.add(Component.text("- 経験値 " + drops.exp(), NamedTextColor.GREEN));
        }
        if (drops.money() != null) {
            lore.add(Component.text("- ゴールド " + drops.money().min() + "-" + drops.money().max(), NamedTextColor.YELLOW));
        }
        boolean hasFixedRewards = drops.exp() > 0 || drops.money() != null;
        int visible = 0;
        for (MobDropItem item : drops.items()) {
            if (item.hidden()) {
                continue;
            }
            if (visible == 0 && hasFixedRewards) {
                lore.add(Component.text(DisplaySeparators.SECTION, NamedTextColor.DARK_GRAY));
            }
            visible++;
            lore.add(Component.text("- " + itemName(item.itemId()) + " x" + item.amount() + " (" + item.rate() + "%)", NamedTextColor.WHITE));
        }
        if (visible == 0 && drops.exp() <= 0 && drops.money() == null) {
            lore.add(Component.text("- なし", NamedTextColor.DARK_GRAY));
        }
    }

    private @NotNull String itemName(@NotNull String itemId) {
        ItemModel item = itemService.findLoadedById(itemId);
        return item == null ? itemId : ColorCodeUtil.toPlainText(item.getName(), itemId);
    }

    private @NotNull Set<String> selectedItemIds(@NotNull List<ItemStack> items) {
        Set<String> ids = new LinkedHashSet<>();
        for (ItemStack item : items) {
            String id = io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory.getAstralItemId(item);
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private boolean isEmptySearchItem(@Nullable ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.isSimilar(searchPlaceholder());
    }

    private @NotNull ItemStack searchPlaceholder() {
        return createItem(
            Material.LIGHT_GRAY_STAINED_GLASS_PANE,
            Component.text(" "),
            List.of()
        );
    }

    private void fill(@NotNull Inventory inventory) {
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler.clone());
        }
    }

    private @NotNull ItemStack backItem() {
        return createItem(
            Material.SPECTRAL_ARROW,
            Component.text("戻る", NamedTextColor.WHITE, TextDecoration.BOLD),
            List.of(Component.text("前の画面へ戻ります", NamedTextColor.GRAY))
        );
    }

    private @NotNull ItemStack createItem(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        return GuiItems.create(material, name, lore);
    }

    private @NotNull Material resolveMaterial(@Nullable String icon, @NotNull Material fallback) {
        if (icon == null || icon.isBlank()) {
            return fallback;
        }
        try {
            return Material.valueOf(icon.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public enum Screen {
        MOB_LIST,
        SEARCH,
        FILTER
    }

    private record Holder(
        @NotNull Screen screen,
        @Nullable AdventureRecordListType listType,
        int pageIndex,
        @NotNull Set<String> searchItemIds,
        @NotNull List<AdventureRecordService.Entry> entries,
        boolean superMode
    ) implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull String getNavigationId() {
            if (screen == Screen.FILTER || (screen == Screen.MOB_LIST && listType != AdventureRecordListType.SEARCH)) {
                return "adventure-record:mob-list";
            }
            String type = listType == null ? "" : ":" + listType.name();
            return "adventure-record:" + screen.name() + type;
        }

        @Override
        public int getBackSlot() {
            return screen == Screen.FILTER ? -1 : BaseMenuScreenView.BACK_SLOT;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
