package io.github.maaasu.astralRecord.feature.menu.view;

import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Bukkit インベントリをメニュー画面として識別するための Holder。
 */
public record MenuInventoryHolder(
    MenuScreen screen,
    int shortcutSlotIndex,
    int pageIndex,
    @Nullable String contentId
) implements HotbarShortcutGuiHolder {
    MenuInventoryHolder(@NotNull MenuScreen screen) {
        this(screen, -1, 0, null);
    }

    MenuInventoryHolder(@NotNull MenuScreen screen, int shortcutSlotIndex) {
        this(screen, shortcutSlotIndex, 0, null);
    }

    public MenuInventoryHolder(@NotNull MenuScreen screen, int shortcutSlotIndex, int pageIndex) {
        this(screen, shortcutSlotIndex, pageIndex, null);
    }

    public MenuInventoryHolder(@NotNull MenuScreen screen, int shortcutSlotIndex, int pageIndex, @Nullable String contentId) {
        this.screen = screen;
        this.shortcutSlotIndex = shortcutSlotIndex;
        this.pageIndex = pageIndex;
        this.contentId = contentId;
    }

    @Override
    @NotNull
    public MenuScreen screen() {
        return screen;
    }

    @Override
    public @NotNull String getNavigationId() {
        String detailId = contentId == null ? "" : ":" + contentId;
        return "menu:" + screen.name() + detailId;
    }

    @Override
    public int getBackSlot() {
        return switch (screen) {
            case MAIN -> MenuView.BACK_SLOT;
            case CLASS, EQUIPMENT_ENHANCE, EQUIPMENT_REPAIR, SELL, STORAGE -> -1;
            case TRASH_CONFIRM -> 14;
            case SELL_CONFIRM -> 22;
            default -> MenuView.BACK_SLOT;
        };
    }

    @Override
    public boolean isDirectBackNavigation() {
        return switch (screen) {
            case EQUIPMENT_GUI, TRASH, TRASH_CONFIRM, SELL_CONFIRM -> false;
            default -> true;
        };
    }

    @Override
    public boolean isAlwaysCloseNavigation() {
        return screen == MenuScreen.MAIN;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return Bukkit.createInventory(this, MenuView.SIZE);
    }
}
