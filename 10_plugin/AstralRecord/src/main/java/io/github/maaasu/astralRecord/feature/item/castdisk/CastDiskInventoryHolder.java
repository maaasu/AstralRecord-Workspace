package io.github.maaasu.astralRecord.feature.item.castdisk;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/** スキルキャストディスク設定 GUI の対象個体を保持します。 */
public record CastDiskInventoryHolder(@NotNull String equipmentInstanceId) implements InventoryHolder {
    @Override
    public @NotNull Inventory getInventory() {
        return Bukkit.createInventory(this, CastDiskGui.SIZE);
    }
}
