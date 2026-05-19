package io.github.maaasu.astralRecord.feature.menu.view;

import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Bukkit インベントリをメニュー画面として識別するための Holder。
 */
record MenuInventoryHolder(MenuScreen screen, int shortcutSlotIndex, int pageIndex) implements InventoryHolder {
    MenuInventoryHolder(@NotNull MenuScreen screen) {
        this(screen, -1, 0);
    }

    MenuInventoryHolder(@NotNull MenuScreen screen, int shortcutSlotIndex) {
        this(screen, shortcutSlotIndex, 0);
    }

    MenuInventoryHolder(@NotNull MenuScreen screen, int shortcutSlotIndex, int pageIndex) {
        this.screen = screen;
        this.shortcutSlotIndex = shortcutSlotIndex;
        this.pageIndex = pageIndex;
    }

    @Override
    @NotNull
    public MenuScreen screen() {
        return screen;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return Bukkit.createInventory(this, MenuView.SIZE);
    }
}
