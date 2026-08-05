package io.github.maaasu.astralRecord.feature.skill.model;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/** スキル忘却 GUI の画面状態を保持します。 */
public record SkillForgetInventoryHolder(
    @NotNull SkillForgetScreen screen,
    int pageIndex,
    @NotNull String learnedSkillId
) implements InventoryHolder {
    public SkillForgetInventoryHolder(@NotNull SkillForgetScreen screen, int pageIndex) {
        this(screen, pageIndex, "");
    }

    public SkillForgetInventoryHolder(@NotNull SkillForgetScreen screen, int pageIndex, @NotNull String learnedSkillId) {
        this.screen = screen;
        this.pageIndex = pageIndex;
        this.learnedSkillId = learnedSkillId;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return Bukkit.createInventory(this, screen == SkillForgetScreen.LIST ? 54 : 27);
    }
}
