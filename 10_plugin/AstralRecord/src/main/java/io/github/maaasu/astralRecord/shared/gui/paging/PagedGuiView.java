package io.github.maaasu.astralRecord.shared.gui.paging;

import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 54 スロット GUI のページング表示とナビゲーションスロットを提供します。
 */
public final class PagedGuiView {
    public static final int SIZE = 54;
    public static final int CONTENT_SLOT_COUNT = 45;
    public static final int PREVIOUS_SLOT = 45;
    public static final int BACK_SLOT = 49;
    public static final int NEXT_SLOT = 53;

    public void render(@NotNull Inventory inventory, @NotNull List<ItemStack> items, int pageIndex) {
        int normalizedPage = normalizePage(pageIndex, items.size());
        clear(inventory);
        renderPageItems(inventory, items, normalizedPage);
        renderNavigation(inventory, items.size(), normalizedPage);
    }

    public int normalizePage(int pageIndex, int itemCount) {
        return GuiPagination.normalizePage(pageIndex, itemCount, CONTENT_SLOT_COUNT);
    }

    public int totalPages(int itemCount) {
        return GuiPagination.totalPages(itemCount, CONTENT_SLOT_COUNT);
    }

    public boolean hasPreviousPage(int pageIndex) {
        return GuiPagination.hasPreviousPage(pageIndex);
    }

    public boolean hasNextPage(int pageIndex, int itemCount) {
        return GuiPagination.hasNextPage(pageIndex, itemCount, CONTENT_SLOT_COUNT);
    }

    private void clear(@NotNull Inventory inventory) {
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, new ItemStack(Material.AIR));
        }
    }

    private void renderPageItems(@NotNull Inventory inventory, @NotNull List<ItemStack> items, int pageIndex) {
        int start = GuiPagination.pageStart(pageIndex, CONTENT_SLOT_COUNT);
        int end = GuiPagination.pageEnd(pageIndex, items.size(), CONTENT_SLOT_COUNT);
        for (int i = start; i < end; i++) {
            ItemStack itemStack = items.get(i);
            if (itemStack != null) {
                inventory.setItem(i - start, itemStack.clone());
            }
        }
    }

    private void renderNavigation(@NotNull Inventory inventory, int itemCount, int pageIndex) {
        ItemStack spacer = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = CONTENT_SLOT_COUNT; slot < SIZE; slot++) {
            inventory.setItem(slot, spacer);
        }

        if (hasPreviousPage(pageIndex)) {
            inventory.setItem(PREVIOUS_SLOT, createItem(
                Material.MAP,
                Component.text("前のページ", NamedTextColor.WHITE, TextDecoration.BOLD),
                List.of(Component.text(pageIndex + " / " + totalPages(itemCount), NamedTextColor.GRAY))
            ));
        }
        inventory.setItem(BACK_SLOT, createItem(
            Material.SPECTRAL_ARROW,
            Component.text("戻る", NamedTextColor.WHITE, TextDecoration.BOLD),
            List.of(Component.text("前の画面へ戻ります", NamedTextColor.GRAY))
        ));
        if (hasNextPage(pageIndex, itemCount)) {
            inventory.setItem(NEXT_SLOT, createItem(
                Material.MAP,
                Component.text("次のページ", NamedTextColor.WHITE, TextDecoration.BOLD),
                List.of(Component.text((pageIndex + 2) + " / " + totalPages(itemCount), NamedTextColor.GRAY))
            ));
        }
    }

    private @NotNull ItemStack createItem(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        return GuiItems.create(material, name, lore);
    }
}
