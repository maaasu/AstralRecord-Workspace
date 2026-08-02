package io.github.maaasu.astralRecord.feature.skill.model;

import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** スキルジェムの新規個体習得確認画面を識別します。 */
public record SkillGemLearnConfirmHolder(
    @NotNull UUID inventoryEntryId,
    @NotNull String skillId
) implements HotbarShortcutGuiHolder {
    @Override
    public @NotNull Inventory getInventory() {
        return Bukkit.createInventory(this, 27);
    }
}
