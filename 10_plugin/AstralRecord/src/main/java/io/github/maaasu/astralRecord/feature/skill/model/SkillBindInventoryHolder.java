package io.github.maaasu.astralRecord.feature.skill.model;

import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

/**
 * スキルバインド GUI の画面状態を保持します。
 */
public record SkillBindInventoryHolder(
    @NotNull SkillBindScreen screen,
    int selectedPresetIndex,
    int pageIndex,
    @NotNull String action,
    int pendingPresetIndex
) implements HotbarShortcutGuiHolder {
    public SkillBindInventoryHolder(@NotNull SkillBindScreen screen, int selectedPresetIndex, int pageIndex) {
        this(screen, selectedPresetIndex, pageIndex, "", -1);
    }

    public SkillBindInventoryHolder(@NotNull SkillBindScreen screen, int selectedPresetIndex, @NotNull String action, int pendingPresetIndex) {
        this(screen, selectedPresetIndex, 0, action, pendingPresetIndex);
    }

    @Override
    public @NotNull String getNavigationId() {
        return "skill-bind";
    }

    @Override
    public int getBackSlot() {
        return screen == SkillBindScreen.MAIN ? 49 : -1;
    }

    @Override
    public boolean isDirectBackNavigation() {
        return false;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return Bukkit.createInventory(this, 54);
    }
}
