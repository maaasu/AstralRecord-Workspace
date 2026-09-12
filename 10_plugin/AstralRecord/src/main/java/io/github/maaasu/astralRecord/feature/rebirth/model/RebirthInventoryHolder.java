package io.github.maaasu.astralRecord.feature.rebirth.model;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/** 転生GUIの画面状態を保持します。 */
public record RebirthInventoryHolder(@NotNull RebirthScreen screen) implements InventoryHolder {
    @Override
    public @NotNull Inventory getInventory() {
        return Bukkit.createInventory(this, 27);
    }
}
