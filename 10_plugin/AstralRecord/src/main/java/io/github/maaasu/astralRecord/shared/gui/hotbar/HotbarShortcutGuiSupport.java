package io.github.maaasu.astralRecord.shared.gui.hotbar;

import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.Nullable;

/**
 * 共通ホットバーショートカット対象 GUI の判定を集約します。
 */
public final class HotbarShortcutGuiSupport {
    private HotbarShortcutGuiSupport() {
        // utility class
    }

    /**
     * 指定 inventory が共通ホットバーショートカット対象 GUI かを返します。
     *
     * @param inventory 判定対象 inventory
     * @return 対象 GUI の場合は true
     */
    public static boolean isManagedGui(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof HotbarShortcutGuiHolder;
    }
}
