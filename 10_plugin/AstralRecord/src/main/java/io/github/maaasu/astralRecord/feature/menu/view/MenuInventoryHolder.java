package io.github.maaasu.astralRecord.feature.menu.view;

import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Bukkit インベントリをメニュー画面として識別するための Holder。
 */
public record MenuInventoryHolder(
    MenuScreen screen,
    int shortcutSlotIndex,
    int pageIndex,
    @Nullable String contentId,
    @Nullable UUID equipmentTargetId,
    boolean equipmentReadOnly
) implements HotbarShortcutGuiHolder {
    MenuInventoryHolder(@NotNull MenuScreen screen) {
        this(screen, -1, 0, null, null, false);
    }

    MenuInventoryHolder(@NotNull MenuScreen screen, int shortcutSlotIndex) {
        this(screen, shortcutSlotIndex, 0, null, null, false);
    }

    public MenuInventoryHolder(@NotNull MenuScreen screen, int shortcutSlotIndex, int pageIndex) {
        this(screen, shortcutSlotIndex, pageIndex, null, null, false);
    }

    public MenuInventoryHolder(@NotNull MenuScreen screen, int shortcutSlotIndex, int pageIndex, @Nullable String contentId) {
        this(screen, shortcutSlotIndex, pageIndex, contentId, null, false);
    }

    /**
     * 装備画面の対象プレイヤーと編集可否を保持する holder を生成します。
     *
     * @param screen 装備画面種別
     * @param shortcutSlotIndex クラフトショートカットの slot。使用しない場合は -1
     * @param pageIndex ページ番号
     * @param contentId 画面固有の内容 ID
     * @param equipmentTargetId 装備表示対象プレイヤー ID
     * @param equipmentReadOnly 他プレイヤー参照時に編集を禁止する場合は true
     */
    public MenuInventoryHolder(
        @NotNull MenuScreen screen,
        int shortcutSlotIndex,
        int pageIndex,
        @Nullable String contentId,
        @Nullable UUID equipmentTargetId,
        boolean equipmentReadOnly
    ) {
        this.screen = screen;
        this.shortcutSlotIndex = shortcutSlotIndex;
        this.pageIndex = pageIndex;
        this.contentId = contentId;
        this.equipmentTargetId = equipmentTargetId;
        this.equipmentReadOnly = equipmentReadOnly;
    }

    @Override
    @NotNull
    public MenuScreen screen() {
        return screen;
    }

    @Override
    public @NotNull String getNavigationId() {
        String detailId = contentId == null ? "" : ":" + contentId;
        String equipmentTarget = equipmentTargetId == null ? "" : ":target=" + equipmentTargetId;
        String readOnly = equipmentReadOnly ? ":readonly" : "";
        return "menu:" + screen.name() + detailId + equipmentTarget + readOnly;
    }

    @Override
    public int getBackSlot() {
        return switch (screen) {
            case MAIN -> MenuView.BACK_SLOT;
            case CLASS, SELL, STORAGE -> -1;
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
