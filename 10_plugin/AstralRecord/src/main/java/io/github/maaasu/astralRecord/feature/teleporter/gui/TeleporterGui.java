package io.github.maaasu.astralRecord.feature.teleporter.gui;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.feature.teleporter.service.TeleporterService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
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

import java.util.ArrayList;
import java.util.List;

/**
 * ウェイストーン間テレポート GUI を描画します。
 */
public final class TeleporterGui {
    public static final int SIZE = 54;
    public static final int CONTENT_SLOT_COUNT = 45;
    public static final int PREVIOUS_SLOT = 45;
    public static final int NEXT_SLOT = 53;

    private final TeleporterService teleporterService;

    public TeleporterGui(@NotNull TeleporterService teleporterService) {
        this.teleporterService = teleporterService;
    }

    /**
     * 指定ウェイストーンを起点に GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     * @param astPlayer AstralRecord プレイヤー
     * @param source 起点ウェイストーン
     * @param pageIndex 表示ページ
     */
    public void open(@NotNull Player player, @NotNull AstPlayer astPlayer, @NotNull WaystoneDefinition source, int pageIndex) {
        List<Entry> entries = teleporterService.listGuiEntries(astPlayer, source);
        int normalizedPage = normalizePage(pageIndex, entries.size());
        List<String> visibleIds = visibleIds(entries, normalizedPage);
        Inventory inventory = Bukkit.createInventory(
                new Holder(source.id(), normalizedPage, visibleIds),
                SIZE,
                ColorCodeUtil.toComponent(source.name(), source.id(), NamedTextColor.AQUA)
        );
        render(inventory, entries, normalizedPage);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * テレポーター GUI かどうかを判定します。
     *
     * @param inventory 判定対象
     * @return テレポーター GUI の場合 true
     */
    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    /**
     * Inventory から Holder を取得します。
     *
     * @param inventory 対象 Inventory
     * @return Holder。対象外の場合は null
     */
    @Nullable
    public Holder holder(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder;
        }
        return null;
    }

    public boolean hasPreviousPage(int pageIndex) {
        return GuiPagination.hasPreviousPage(pageIndex);
    }

    public boolean hasNextPage(int pageIndex, int itemCount) {
        return GuiPagination.hasNextPage(pageIndex, itemCount, CONTENT_SLOT_COUNT);
    }

    public int totalPages(int itemCount) {
        return GuiPagination.totalPages(itemCount, CONTENT_SLOT_COUNT);
    }

    public int normalizePage(int pageIndex, int itemCount) {
        return GuiPagination.normalizePage(pageIndex, itemCount, CONTENT_SLOT_COUNT);
    }

    private void render(@NotNull Inventory inventory, @NotNull List<Entry> entries, int pageIndex) {
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, new ItemStack(Material.AIR));
        }
        int start = GuiPagination.pageStart(pageIndex, CONTENT_SLOT_COUNT);
        int end = GuiPagination.pageEnd(pageIndex, entries.size(), CONTENT_SLOT_COUNT);
        for (int i = start; i < end; i++) {
            inventory.setItem(i - start, entryItem(entries.get(i)));
        }
        ItemStack spacer = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = CONTENT_SLOT_COUNT; slot < SIZE; slot++) {
            inventory.setItem(slot, spacer);
        }
        if (hasPreviousPage(pageIndex)) {
            inventory.setItem(PREVIOUS_SLOT, createItem(Material.MAP, Component.text("前のページ", NamedTextColor.WHITE, TextDecoration.BOLD), List.of()));
        }
        if (hasNextPage(pageIndex, entries.size())) {
            inventory.setItem(NEXT_SLOT, createItem(Material.MAP, Component.text("次のページ", NamedTextColor.WHITE, TextDecoration.BOLD), List.of()));
        }
    }

    @NotNull
    private ItemStack entryItem(@NotNull Entry entry) {
        WaystoneDefinition definition = entry.definition();
        if (entry.unlocked()) {
            return createItem(
                    Material.BEACON,
                    ColorCodeUtil.toComponent(definition.name(), definition.id(), NamedTextColor.AQUA),
                    List.of(
                            Component.text("クリックでテレポート", NamedTextColor.GREEN)
                    )
            );
        }
        return createItem(
                Material.IRON_BARS,
                ColorCodeUtil.toComponent(definition.name(), definition.id(), NamedTextColor.DARK_GRAY),
                List.of(
                        Component.text("未解除のウェイストーンです", NamedTextColor.RED),
                        Component.text("必要ゴールド: " + definition.unlockGold(), NamedTextColor.GOLD)
                )
        );
    }

    @NotNull
    private List<String> visibleIds(@NotNull List<Entry> entries, int pageIndex) {
        List<String> ids = new ArrayList<>();
        int start = GuiPagination.pageStart(pageIndex, CONTENT_SLOT_COUNT);
        int end = GuiPagination.pageEnd(pageIndex, entries.size(), CONTENT_SLOT_COUNT);
        for (int i = start; i < end; i++) {
            ids.add(entries.get(i).definition().id());
        }
        return ids;
    }

    @NotNull
    private ItemStack createItem(@NotNull Material material, @NotNull Component name, @NotNull List<Component> lore) {
        return GuiItems.create(material, name, lore);
    }

    public record Entry(@NotNull WaystoneDefinition definition, boolean unlocked) {
    }

    public record Holder(@NotNull String sourceWaystoneId, int pageIndex, @NotNull List<String> visibleWaystoneIds) implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
