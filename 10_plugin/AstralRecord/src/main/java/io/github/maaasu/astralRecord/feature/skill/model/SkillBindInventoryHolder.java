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
    int pendingPresetIndex,
    @NotNull String learnedSkillId
) implements HotbarShortcutGuiHolder {
    public SkillBindInventoryHolder(@NotNull SkillBindScreen screen, int selectedPresetIndex, int pageIndex) {
        this(screen, selectedPresetIndex, pageIndex, "", -1, "");
    }

    public SkillBindInventoryHolder(@NotNull SkillBindScreen screen, int selectedPresetIndex, @NotNull String action, int pendingPresetIndex) {
        this(screen, selectedPresetIndex, 0, action, pendingPresetIndex, "");
    }

    public SkillBindInventoryHolder(
        @NotNull SkillBindScreen screen,
        int selectedPresetIndex,
        int pageIndex,
        @NotNull String learnedSkillId
    ) {
        this(screen, selectedPresetIndex, pageIndex, "", -1, learnedSkillId);
    }

    @Override
    public @NotNull String getNavigationId() {
        return "skill-bind";
    }

    @Override
    public int getBackSlot() {
        // 合成画面の slot 49 はスキルマネージャーへ戻る専用操作です。
        // 共有ナビゲーションに渡すと、履歴なしで GUI 自体が閉じてしまいます。
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
