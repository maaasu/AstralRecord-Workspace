package io.github.maaasu.astralRecord.feature.adventurerecord.gui;

import io.github.maaasu.astralRecord.feature.adventurerecord.model.AdventureRecordListType;
import io.github.maaasu.astralRecord.feature.adventurerecord.service.AdventureRecordService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.loot.model.LootEntry;
import io.github.maaasu.astralRecord.feature.loot.model.LootModel;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.mob.model.MobBaseStat;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropItem;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
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
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    public static final int MOB_HEAD_SLOT = 4;
    public static final int MOB_RESOURCE_SLOT = 10;
    public static final int MOB_PRIMARY_SLOT = 11;
    public static final int MOB_OFFENSE_SLOT = 12;
    public static final int MOB_DEFENSE_SLOT = 13;
    public static final int MOB_ELEMENT_SLOT = 14;
    public static final int MOB_CONDITION_SLOT = 15;
    public static final int MOB_UTILITY_SLOT = 16;
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
    private final LootService lootService;

    /**
     * 冒険記録 GUI を構築します。
     *
     * @param itemService ロード済みアイテムの表示名解決サービス
     * @param lootService ロード済み LootTable の表示候補解決サービス
     */
    public AdventureRecordGui(@NotNull ItemService itemService, @NotNull LootService lootService) {
        this.itemService = itemService;
        this.lootService = lootService;
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
                superMode,
                null,
                null
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
            new Holder(Screen.FILTER, selectedType, 0, Set.of(), List.of(), false, null, null),
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
            new Holder(Screen.SEARCH, null, 0, selectedItemIds(selectedItems), List.of(), false, null, null),
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

    /**
     * 選択した Mob の情報 GUI を開きます。
     *
     * <p>ステータスはワールド上の個体ではなく、冒険記録 entry が保持する
     * {@link MobTemplate} の基礎値を表示します。</p>
     *
     * @param player 表示対象プレイヤー
     * @param entry 選択された冒険記録 entry
     */
    public void openMobDetail(
        @NotNull Player player,
        @NotNull AdventureRecordService.Entry entry
    ) {
        MobTemplate template = entry.template();
        Inventory inventory = Bukkit.createInventory(
            new Holder(Screen.MOB_DETAIL, null, 0, Set.of(), List.of(), false, entry, null),
            SIZE,
            Component.text("モブ情報: " + mobDisplayName(template), NamedTextColor.GOLD)
        );
        renderMobDetail(inventory, entry);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * 指定した Mob のステータス詳細 GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     * @param entry 表示する冒険記録 entry
     * @param category 表示するステータスカテゴリ
     * @param pageIndex 0 始まりページ
     */
    public void openMobStatusDetail(
        @NotNull Player player,
        @NotNull AdventureRecordService.Entry entry,
        @NotNull StatusType.Category category,
        int pageIndex
    ) {
        Map<StatusType, Double> values = mobStatusValues(entry.template());
        List<StatusType> statuses = statusesInCategory(category, values);
        int normalizedPage = pagedGuiView.normalizePage(pageIndex, statuses.size());
        Inventory inventory = Bukkit.createInventory(
            new Holder(Screen.MOB_STATUS_DETAIL, null, normalizedPage, Set.of(), List.of(), false, entry, category),
            PagedGuiView.SIZE,
            Component.text(
                "モブステータス: " + mobDisplayName(entry.template()) + " / " + category.getDisplayName(),
                NamedTextColor.GOLD
            )
        );
        List<ItemStack> items = statuses.stream()
            .map(type -> statusDetailItem(type, values.get(type)))
            .toList();
        pagedGuiView.render(inventory, items, normalizedPage);
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
     * Mob 一覧のクリック位置に対応する entry を返します。
     *
     * @param inventory 判定対象の一覧 GUI
     * @param rawSlot クリックされた raw slot
     * @return 対応する entry。対象外または空 slot の場合は null
     */
    public @Nullable AdventureRecordService.Entry getEntryAtSlot(
        @Nullable Inventory inventory,
        int rawSlot
    ) {
        if (inventory == null || rawSlot < 0 || rawSlot >= PagedGuiView.CONTENT_SLOT_COUNT) {
            return null;
        }
        if (!(inventory.getHolder() instanceof Holder holder) || holder.screen() != Screen.MOB_LIST) {
            return null;
        }
        int entryIndex = holder.pageIndex() * PagedGuiView.CONTENT_SLOT_COUNT + rawSlot;
        return entryIndex >= 0 && entryIndex < holder.entries().size()
            ? holder.entries().get(entryIndex)
            : null;
    }

    /**
     * Mob 詳細 GUI が保持する entry を返します。
     *
     * @param inventory 判定対象の GUI
     * @return 詳細表示中の entry。対象外の場合は null
     */
    public @Nullable AdventureRecordService.Entry getMobEntry(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder
            && (holder.screen() == Screen.MOB_DETAIL || holder.screen() == Screen.MOB_STATUS_DETAIL)) {
            return holder.mobEntry();
        }
        return null;
    }

    /**
     * Mob ステータス詳細 GUI が表示しているカテゴリを返します。
     *
     * @param inventory 判定対象の GUI
     * @return 表示中のカテゴリ。対象外の場合は null
     */
    public @Nullable StatusType.Category getMobStatusCategory(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder
            && holder.screen() == Screen.MOB_STATUS_DETAIL) {
            return holder.statusCategory();
        }
        return null;
    }

    /**
     * Mob 詳細 GUI のクリック位置からステータスカテゴリを解決します。
     *
     * @param rawSlot クリックされた raw slot
     * @return 対応するカテゴリ。カテゴリ slot 以外の場合は null
     */
    public @Nullable StatusType.Category getMobStatusCategoryAtSlot(int rawSlot) {
        return switch (rawSlot) {
            case MOB_RESOURCE_SLOT -> StatusType.Category.RESOURCE;
            case MOB_PRIMARY_SLOT -> StatusType.Category.PRIMARY;
            case MOB_OFFENSE_SLOT -> StatusType.Category.OFFENSE;
            case MOB_DEFENSE_SLOT -> StatusType.Category.DEFENSE;
            case MOB_ELEMENT_SLOT -> StatusType.Category.ELEMENT;
            case MOB_CONDITION_SLOT -> StatusType.Category.CONDITION;
            case MOB_UTILITY_SLOT -> StatusType.Category.UTILITY;
            default -> null;
        };
    }

    /**
     * Mob ステータス詳細 GUI の表示項目数を返します。
     *
     * @param inventory 判定対象のステータス詳細 GUI
     * @return 現在カテゴリに属する非ゼロ基礎ステータス数
     */
    public int getMobStatusDetailItemCount(@Nullable Inventory inventory) {
        if (inventory == null || !(inventory.getHolder() instanceof Holder holder)
            || holder.screen() != Screen.MOB_STATUS_DETAIL
            || holder.mobEntry() == null || holder.statusCategory() == null) {
            return 0;
        }
        return statusesInCategory(
            holder.statusCategory(),
            mobStatusValues(holder.mobEntry().template())
        ).size();
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
            mobDisplayName(template),
            NamedTextColor.WHITE
        ), lore);
    }

    private void renderMobDetail(
        @NotNull Inventory inventory,
        @NotNull AdventureRecordService.Entry entry
    ) {
        fill(inventory);
        inventory.setItem(PagedGuiView.BACK_SLOT, backItem());
        inventory.setItem(MOB_HEAD_SLOT, mobProfileItem(entry));
        Map<StatusType, Double> values = mobStatusValues(entry.template());
        inventory.setItem(MOB_RESOURCE_SLOT, mobCategoryItem(
            Material.GOLDEN_APPLE, "◆", StatusType.Category.RESOURCE, NamedTextColor.GOLD, values
        ));
        inventory.setItem(MOB_PRIMARY_SLOT, mobCategoryItem(
            Material.DIAMOND, "◇", StatusType.Category.PRIMARY, NamedTextColor.YELLOW, values
        ));
        inventory.setItem(MOB_OFFENSE_SLOT, mobCategoryItem(
            Material.NETHERITE_SWORD, "⚔", StatusType.Category.OFFENSE, NamedTextColor.RED, values
        ));
        inventory.setItem(MOB_DEFENSE_SLOT, mobCategoryItem(
            Material.SHIELD, "✚", StatusType.Category.DEFENSE, NamedTextColor.BLUE, values
        ));
        inventory.setItem(MOB_ELEMENT_SLOT, mobCategoryItem(
            Material.PRISMARINE_CRYSTALS, "✧", StatusType.Category.ELEMENT, NamedTextColor.LIGHT_PURPLE, values
        ));
        inventory.setItem(MOB_CONDITION_SLOT, mobCategoryItem(
            Material.FERMENTED_SPIDER_EYE, "☣", StatusType.Category.CONDITION, NamedTextColor.DARK_PURPLE, values
        ));
        inventory.setItem(MOB_UTILITY_SLOT, mobCategoryItem(
            Material.FEATHER, "✦", StatusType.Category.UTILITY, NamedTextColor.GREEN, values
        ));
    }

    private @NotNull ItemStack mobProfileItem(@NotNull AdventureRecordService.Entry entry) {
        MobTemplate template = entry.template();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("種類: " + categoryLabel(template.category()), NamedTextColor.AQUA));
        lore.add(Component.text("レベル: " + template.level(), NamedTextColor.YELLOW));
        if (template.title() != null && !template.title().isBlank()) {
            lore.add(Component.text(
                "称号: " + ColorCodeUtil.toPlainText(template.title(), "未登録の称号"),
                NamedTextColor.LIGHT_PURPLE
            ));
        }
        if (entry.record() == null) {
            lore.add(Component.text("未討伐", NamedTextColor.RED));
        } else {
            lore.add(Component.text("討伐数: " + entry.record().defeatCount(), NamedTextColor.GOLD));
            lore.add(Component.text(
                "最新討伐: " + DATE_FORMATTER.format(entry.record().lastDefeatedAt()),
                NamedTextColor.GRAY
            ));
        }
        lore.add(Component.text(DisplaySeparators.SECTION, NamedTextColor.DARK_GRAY));
        lore.add(Component.text("ステータスはモブマスタの基礎値", NamedTextColor.GRAY));
        if (!template.lore().isEmpty()) {
            lore.add(Component.text("説明", NamedTextColor.AQUA));
            template.lore().stream().limit(4)
                .map(line -> ColorCodeUtil.toPlainText(line, line))
                .forEach(line -> lore.add(Component.text("- " + line, NamedTextColor.WHITE)));
        }
        return createItem(
            resolveMaterial(template.icon(), Material.ZOMBIE_HEAD),
            Component.text(mobDisplayName(template), NamedTextColor.WHITE),
            lore
        );
    }

    private @NotNull ItemStack mobCategoryItem(
        @NotNull Material material,
        @NotNull String icon,
        @NotNull StatusType.Category category,
        @NotNull NamedTextColor color,
        @NotNull Map<StatusType, Double> values
    ) {
        Component name = Component.empty()
            .append(Component.text(icon + " ", color))
            .append(Component.text(category.getDisplayName(), color, TextDecoration.BOLD))
            .append(Component.text(" " + icon, color));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(DisplaySeparators.SECTION, NamedTextColor.DARK_GRAY));
        List<StatusType> statuses = statusesInCategory(category, values);
        for (StatusType type : statuses) {
            lore.add(mobStatLine(type, values.get(type)));
        }
        if (statuses.isEmpty()) {
            lore.add(Component.text("ステータス情報はありません", NamedTextColor.GRAY));
        }
        lore.add(Component.text(DisplaySeparators.SECTION, NamedTextColor.DARK_GRAY));
        return createItem(material, name, lore);
    }

    private @NotNull Component mobStatLine(@NotNull StatusType type, double value) {
        return Component.empty()
            .append(Component.text(" ▸ ", type.namedColor()))
            .append(Component.text(type.getDisplayName(), type.namedColor()))
            .append(Component.text("  "))
            .append(Component.text(type.formatValue(value), NamedTextColor.WHITE, TextDecoration.BOLD));
    }

    private @NotNull ItemStack statusDetailItem(@NotNull StatusType type, double value) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(type.getDescription(), NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(Component.text(
            "基礎値: " + type.formatValue(value),
            NamedTextColor.WHITE,
            TextDecoration.BOLD
        ));
        return createItem(
            statusMaterial(type),
            Component.text(type.getDisplayName(), type.namedColor(), TextDecoration.BOLD),
            lore
        );
    }

    private @NotNull Material statusMaterial(@NotNull StatusType type) {
        return switch (type.getCategory()) {
            case RESOURCE -> Material.GOLDEN_APPLE;
            case PRIMARY -> Material.DIAMOND;
            case OFFENSE -> Material.NETHERITE_SWORD;
            case DEFENSE -> Material.SHIELD;
            case ELEMENT -> Material.PRISMARINE_CRYSTALS;
            case CONDITION -> Material.FERMENTED_SPIDER_EYE;
            case UTILITY -> Material.FEATHER;
        };
    }

    private @NotNull Map<StatusType, Double> mobStatusValues(@NotNull MobTemplate template) {
        Map<StatusType, Double> values = new EnumMap<>(StatusType.class);
        for (MobBaseStat baseStat : template.baseStats()) {
            if (baseStat == null || baseStat.status() == null) {
                continue;
            }
            StatusType type = StatusType.fromId(baseStat.status());
            if (type != null) {
                values.put(type, baseStat.value());
            }
        }
        return values;
    }

    private @NotNull List<StatusType> statusesInCategory(
        @NotNull StatusType.Category category,
        @NotNull Map<StatusType, Double> values
    ) {
        return StatusType.byCategory(category).stream()
            .filter(type -> values.containsKey(type) && values.get(type) != 0.0D)
            .toList();
    }

    private @NotNull String mobDisplayName(@NotNull MobTemplate template) {
        return ColorCodeUtil.toPlainText(template.displayName(), "未登録のモブ");
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
        LootModel loot = resolveLootTable(drops.lootTable());
        if (loot != null) {
            for (LootEntry entry : loot.flattenedEntries()) {
                if (visible == 0 && hasFixedRewards) {
                    lore.add(Component.text(DisplaySeparators.SECTION, NamedTextColor.DARK_GRAY));
                }
                visible++;
                String amount = entry.getMinAmount() == entry.getMaxAmount()
                    ? Integer.toString(entry.getMinAmount())
                    : entry.getMinAmount() + "~" + entry.getMaxAmount();
                lore.add(Component.text(
                    "- " + itemName(entry.getItemId()) + " x" + amount + " (" + entry.getWeight() + "%)",
                    NamedTextColor.WHITE
                ));
            }
        }
        if (visible == 0 && drops.exp() <= 0 && drops.money() == null) {
            lore.add(Component.text("- なし", NamedTextColor.DARK_GRAY));
        }
    }

    private @Nullable LootModel resolveLootTable(@Nullable String lootTableId) {
        if (lootTableId == null || lootTableId.isBlank()) {
            return null;
        }
        return lootService.getLoaded(lootTableId);
    }

    private @NotNull String itemName(@NotNull String itemId) {
        ItemModel item = itemService.findLoadedById(itemId);
        return item == null
            ? "未登録のアイテム"
            : ColorCodeUtil.toPlainText(item.getName(), "未登録のアイテム");
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
        MOB_DETAIL,
        MOB_STATUS_DETAIL,
        SEARCH,
        FILTER
    }

    private record Holder(
        @NotNull Screen screen,
        @Nullable AdventureRecordListType listType,
        int pageIndex,
        @NotNull Set<String> searchItemIds,
        @NotNull List<AdventureRecordService.Entry> entries,
        boolean superMode,
        @Nullable AdventureRecordService.Entry mobEntry,
        @Nullable StatusType.Category statusCategory
    ) implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull String getNavigationId() {
            if (screen == Screen.MOB_DETAIL) {
                String mobId = mobEntry == null ? "" : mobEntry.template().id();
                return "adventure-record:mob-detail:" + mobId;
            }
            if (screen == Screen.MOB_STATUS_DETAIL) {
                String mobId = mobEntry == null ? "" : mobEntry.template().id();
                String category = statusCategory == null ? "" : ":" + statusCategory.name();
                return "adventure-record:mob-status-detail:" + mobId + category;
            }
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
