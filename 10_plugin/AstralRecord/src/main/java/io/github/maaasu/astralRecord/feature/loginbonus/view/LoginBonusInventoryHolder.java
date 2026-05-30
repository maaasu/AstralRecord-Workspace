package io.github.maaasu.astralRecord.feature.loginbonus.view;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * ログインボーナス GUI を識別する Holder。
 */
public final class LoginBonusInventoryHolder implements InventoryHolder {
    static final int SIZE = 27;

    @Override
    public @NotNull Inventory getInventory() {
        return Bukkit.createInventory(this, SIZE);
    }
}
