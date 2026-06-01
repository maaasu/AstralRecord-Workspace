package io.github.maaasu.astralRecord.feature.adventurerecord.gui;

import io.github.maaasu.astralRecord.feature.adventurerecord.model.AdventureRecordListType;
import io.github.maaasu.astralRecord.feature.adventurerecord.service.AdventureRecordService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropItem;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.paging.PagedGuiView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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
    public static final int ENEMY_RECORD_SLOT = 20;
    public static final int BOSS_RECORD_SLOT = 22;
    public static final int MOB_SEARCH_SLOT = 24;
    public static final int BOND_RECORD_SLOT = 31;
    public static final int SEARCH_BUTTON_SLOT = 53;
    public static final int SEARCH_BACK_SLOT = BaseMenuScreenView.BACK_SLOT;
    public static final int SEARCH_CLOSE_SLOT = BaseMenuScreenView.CLOSE_SLOT;
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
     * 冒険記録トップ GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     */
    public void openMain(@NotNull Player player) {
        Inventory inventory = Bukkit.createInventory(
            new Holder(Screen.MAIN, null, 0, Set.of()),
            SIZE,
            Component.text("冒険記録", NamedTextColor.GOLD)
        );
        fill(inventory);
        inventory.setItem(ENEMY_RECORD_SLOT, createItem(
            Material.ZOMBIE_HEAD,
            Component.text("魔物録", NamedTextColor.GREEN),
            List.of(Component.text("討伐したエネミーの記録", NamedTextColor.GRAY))
        ));
        inventory.setItem(BOSS_RECORD_SLOT, createItem(
            Material.DRAGON_HEAD,
            Component.text("厄災録", NamedTextColor.RED),
            List.of(Component.text("討伐したボスの記録", NamedTextColor.GRAY))
        ));
        inventory.setItem(MOB_SEARCH_SLOT, createItem(
            Material.COMPASS,
            Component.text("モブ検索", NamedTextColor.AQUA),
            List.of(Component.text("指定アイテムをドロップするモブを検索", NamedTextColor.GRAY))
        ));
        inventory.setItem(BOND_RECORD_SLOT, createItem(
            Material.AMETHYST_SHARD,
            Component.text("絆記録", NamedTextColor.LIGHT_PURPLE),
            List.of(Component.text("未実装", NamedTextColor.DARK_GRAY))
        ));
        inventory.setItem(BaseMenuScreenView.BACK_SLOT, backItem());
        player.openInventory(inventory);
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
            new Holder(Screen.MOB_LIST, listType, normalizedPage, searchItemIds),
            SIZE,
            Component.text(listType.getTitle() + " " + (normalizedPage + 1) + "/" + totalPages, NamedTextColor.GOLD)
        );
        pagedGuiView.render(inventory, icons, normalizedPage);
        player.openInventory(inventory);
    }

    /**
     * Mob 検索条件 GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     * @param selectedItems 検索対象アイテム
     */
    public void openSearch(@NotNull Player player, @NotNull List<ItemStack> selectedItems) {
        Inventory inventory = Bukkit.createInventory(
            new Holder(Screen.SEARCH, null, 0, selectedItemIds(selectedItems)),
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
        player.openInventory(inventory);
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
        lore.add(Component.text("ID: " + template.id(), NamedTextColor.DARK_GRAY));
        lore.add(Component.text("Lv: " + template.level(), NamedTextColor.YELLOW));
        if (superMode) {
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
                .map(line -> ColorCodeUtil.stripColor(ColorCodeUtil.translateAlternateColorCodes(line)))
                .forEach(line -> lore.add(Component.text("- " + line, NamedTextColor.WHITE)));
        }
        appendDrops(lore, template.drops());
        return createItem(resolveMaterial(template.icon(), Material.ZOMBIE_HEAD), Component.text(
            ColorCodeUtil.stripColor(ColorCodeUtil.translateAlternateColorCodes(template.displayName())),
            NamedTextColor.WHITE
        ), lore);
    }

    private void appendDrops(@NotNull List<Component> lore, @Nullable MobDropConfig drops) {
        if (drops == null) {
            lore.add(Component.text("ドロップ: なし", NamedTextColor.DARK_GRAY));
            return;
        }
        lore.add(Component.text("ドロップ", NamedTextColor.AQUA));
        if (drops.exp() > 0) {
            lore.add(Component.text("- EXP " + drops.exp(), NamedTextColor.GREEN));
        }
        if (drops.money() != null) {
            lore.add(Component.text("- Gold " + drops.money().min() + "-" + drops.money().max(), NamedTextColor.YELLOW));
        }
        int visible = 0;
        for (MobDropItem item : drops.items()) {
            if (item.hidden()) {
                continue;
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
        return item == null ? itemId : ColorCodeUtil.stripColor(ColorCodeUtil.translateAlternateColorCodes(item.getName()));
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
            List.of(
                Component.text("前の画面へ戻ります", NamedTextColor.GRAY),
                Component.text("navigation", NamedTextColor.DARK_GRAY)
            )
        );
    }

    private @NotNull ItemStack closeItem() {
        return createItem(Material.BARRIER, Component.text("閉じる", NamedTextColor.RED), List.of());
    }

    private @NotNull ItemStack createItem(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic(name));
            meta.lore(lore.stream().map(this::noItalic).toList());
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private @NotNull Component noItalic(@NotNull Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
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
        MAIN,
        MOB_LIST,
        SEARCH
    }

    private record Holder(
        @NotNull Screen screen,
        @Nullable AdventureRecordListType listType,
        int pageIndex,
        @NotNull Set<String> searchItemIds
    ) implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
