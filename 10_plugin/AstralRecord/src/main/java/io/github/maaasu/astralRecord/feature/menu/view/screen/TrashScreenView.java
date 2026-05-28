package io.github.maaasu.astralRecord.feature.menu.view.screen;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class TrashScreenView extends BaseMenuScreenView {
    public static final int CONTENT_SLOT_COUNT = 45;
    public static final int PREVIOUS_SLOT = 45;
    public static final int GUIDE_SLOT = 47;
    public static final int NEXT_SLOT = 53;
    public static final int CLOSE_SLOT = 51;

    public void render(@NotNull Inventory inventory, @NotNull List<ItemStack> items, int pageIndex) {
        int normalizedPage = normalizePage(pageIndex, items.size());
        clear(inventory);
        renderItems(inventory, items, normalizedPage);
        renderNavigation(inventory, items.size(), normalizedPage);
    }

    public int normalizePage(int pageIndex, int itemCount) {
        return Math.clamp(pageIndex, 0, totalPages(itemCount) - 1);
    }

    public int totalPages(int itemCount) {
        return Math.max(1, (int) Math.ceil(itemCount / (double) CONTENT_SLOT_COUNT));
    }

    public boolean hasPreviousPage(int pageIndex) {
        return pageIndex > 0;
    }

    public boolean hasNextPage(int pageIndex, int itemCount) {
        return pageIndex + 1 < totalPages(itemCount);
    }

    private void clear(@NotNull Inventory inventory) {
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, new ItemStack(Material.AIR));
        }
    }

    private void renderItems(@NotNull Inventory inventory, @NotNull List<ItemStack> items, int pageIndex) {
        int start = pageIndex * CONTENT_SLOT_COUNT;
        int end = Math.min(start + CONTENT_SLOT_COUNT, items.size());
        for (int i = start; i < end; i++) {
            ItemStack itemStack = items.get(i);
            if (itemStack != null && itemStack.getType() != Material.AIR) {
                inventory.setItem(i - start, cloneWithAmountLore(itemStack));
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
                Component.text("前のページ", NamedTextColor.WHITE),
                List.of(Component.text(pageIndex + " / " + totalPages(itemCount), NamedTextColor.GRAY))
            ));
        }
        inventory.setItem(BACK_SLOT, backItem());
        inventory.setItem(GUIDE_SLOT, createItem(
            Material.PAPER,
            Component.text("ゴミ箱ガイド", NamedTextColor.YELLOW),
            List.of(
                Component.text("左クリック: 1個ゴミ箱へ", NamedTextColor.GRAY),
                Component.text("Shift+左クリック: すべてゴミ箱へ", NamedTextColor.GRAY),
                Component.text("右クリック: 半分ゴミ箱へ", NamedTextColor.GRAY),
                Component.text("ゴミ箱→インベントリも同様", NamedTextColor.GRAY),
                Component.text(" ", NamedTextColor.GRAY),
                Component.text("GUIを閉じるとインベントリに戻ります", NamedTextColor.GRAY),
                Component.text("廃棄ボタンで廃棄確認画面へ", NamedTextColor.GRAY)
            )
        ));
        inventory.setItem(CLOSE_SLOT, createItem(
            Material.BARRIER,
            Component.text("廃棄", NamedTextColor.RED),
            List.of(Component.text("廃棄確認画面を開く", NamedTextColor.GRAY))
        ));
        if (hasNextPage(pageIndex, itemCount)) {
            inventory.setItem(NEXT_SLOT, createItem(
                Material.MAP,
                Component.text("次のページ", NamedTextColor.WHITE),
                List.of(Component.text((pageIndex + 2) + " / " + totalPages(itemCount), NamedTextColor.GRAY))
            ));
        }
    }
}
