package io.github.maaasu.astralRecord.feature.world.gui;

import io.github.maaasu.astralRecord.feature.world.model.WorldAdventureGuide;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
import java.util.Locale;

/**
 * 拠点ワールドからオーバーワールドを選ぶ GUI です。
 */
public final class OverworldTeleportGui {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    public static final int SIZE = 54;
    public static final int CONTENT_SLOT_COUNT = 45;
    public static final int PREVIOUS_SLOT = 45;
    public static final int NEXT_SLOT = 53;

    /**
     * GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     * @param destinations 表示するワールド一覧
     * @param pageIndex 表示ページ
     */
    public void open(@NotNull Player player, @NotNull List<WorldMasterData> destinations, int pageIndex) {
        int normalizedPage = normalizePage(pageIndex, destinations.size());
        List<String> visibleIds = visibleIds(destinations, normalizedPage);
        Inventory inventory = Bukkit.createInventory(
                new Holder(normalizedPage, visibleIds),
                SIZE,
                Component.text("オーバーワールド転送", NamedTextColor.AQUA, TextDecoration.BOLD)
        );
        render(inventory, destinations, normalizedPage);
        player.openInventory(inventory);
    }

    /**
     * この GUI かを返します。
     *
     * @param inventory 対象 Inventory
     * @return この GUI の場合は {@code true}
     */
    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    /**
     * Holder を取り出します。
     *
     * @param inventory 対象 Inventory
     * @return GUI Holder。対象外なら {@code null}
     */
    public @Nullable Holder holder(@Nullable Inventory inventory) {
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

    public int normalizePage(int pageIndex, int itemCount) {
        return GuiPagination.normalizePage(pageIndex, itemCount, CONTENT_SLOT_COUNT);
    }

    private void render(@NotNull Inventory inventory, @NotNull List<WorldMasterData> destinations, int pageIndex) {
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, new ItemStack(Material.AIR));
        }

        int start = GuiPagination.pageStart(pageIndex, CONTENT_SLOT_COUNT);
        int end = GuiPagination.pageEnd(pageIndex, destinations.size(), CONTENT_SLOT_COUNT);
        for (int index = start; index < end; index++) {
            inventory.setItem(index - start, destinationItem(destinations.get(index)));
        }

        ItemStack spacer = GuiItems.create(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = CONTENT_SLOT_COUNT; slot < SIZE; slot++) {
            inventory.setItem(slot, spacer);
        }
        if (hasPreviousPage(pageIndex)) {
            inventory.setItem(PREVIOUS_SLOT, GuiItems.create(
                    Material.MAP,
                    Component.text("前のページ", NamedTextColor.WHITE, TextDecoration.BOLD),
                    List.of()
            ));
        }
        if (hasNextPage(pageIndex, destinations.size())) {
            inventory.setItem(NEXT_SLOT, GuiItems.create(
                    Material.MAP,
                    Component.text("次のページ", NamedTextColor.WHITE, TextDecoration.BOLD),
                    List.of()
            ));
        }
    }

    private @NotNull ItemStack destinationItem(@NotNull WorldMasterData world) {
        List<Component> lore = new ArrayList<>();
        if (!world.description().isBlank()) {
            lore.add(legacy(world.description(), world.id()));
        }

        WorldAdventureGuide guide = world.adventureGuide();
        if (guide != null) {
            if (guide.hasRecommendedLevel()) {
                lore.add(Component.text("推奨レベル: ", NamedTextColor.GRAY)
                        .append(Component.text(formatRange("Lv.", guide.recommendedLevelMin(), guide.recommendedLevelMax()), NamedTextColor.GOLD))
                        .decoration(TextDecoration.ITALIC, false));
            }
            if (guide.hasRecommendedPartySize()) {
                lore.add(Component.text("推奨人数: ", NamedTextColor.GRAY)
                        .append(Component.text(formatRange("", guide.recommendedPartySizeMin(), guide.recommendedPartySizeMax()) + "人", NamedTextColor.GREEN))
                        .decoration(TextDecoration.ITALIC, false));
            }
            for (String note : guide.notes()) {
                lore.add(Component.text("・", NamedTextColor.DARK_GRAY)
                        .append(legacy(note, note))
                        .decoration(TextDecoration.ITALIC, false));
            }
        }

        lore.add(Component.text("クリックで移動", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        return GuiItems.create(
                resolveIcon(world.guiIconMaterial()),
                legacy(world.displayName(), world.id()).decorate(TextDecoration.BOLD),
                lore
        );
    }

    private @NotNull List<String> visibleIds(@NotNull List<WorldMasterData> destinations, int pageIndex) {
        List<String> ids = new ArrayList<>();
        int start = GuiPagination.pageStart(pageIndex, CONTENT_SLOT_COUNT);
        int end = GuiPagination.pageEnd(pageIndex, destinations.size(), CONTENT_SLOT_COUNT);
        for (int index = start; index < end; index++) {
            ids.add(destinations.get(index).id());
        }
        return ids;
    }

    private @NotNull Component legacy(@NotNull String text, @NotNull String fallback) {
        return GuiItems.noItalic(LEGACY.deserialize(ColorCodeUtil.toLegacyText(text, fallback)));
    }

    private @NotNull Material resolveIcon(@Nullable String materialName) {
        if (materialName != null && !materialName.isBlank()) {
            Material resolved = Material.matchMaterial(materialName.trim().toUpperCase(Locale.ROOT));
            if (resolved != null && resolved.isItem()) {
                return resolved;
            }
        }
        return Material.GRASS_BLOCK;
    }

    private @NotNull String formatRange(@NotNull String prefix, @Nullable Integer min, @Nullable Integer max) {
        if (min != null && max != null) {
            return prefix + min + "-" + max;
        }
        if (min != null) {
            return prefix + min + "+";
        }
        if (max != null) {
            return prefix + "1-" + max;
        }
        return prefix + "-";
    }

    public record Holder(int pageIndex, @NotNull List<String> visibleWorldIds) implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
