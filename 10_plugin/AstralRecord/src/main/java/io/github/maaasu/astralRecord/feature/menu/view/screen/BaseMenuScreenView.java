package io.github.maaasu.astralRecord.feature.menu.view.screen;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public abstract class BaseMenuScreenView {
    public static final int SIZE = 54;
    public static final int CLOSE_SLOT = -1;
    public static final int BACK_SLOT = 49;

    protected void fill(@NotNull Inventory inventory) {
        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        ItemStack panel = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            boolean isBorder = slot < 9 || slot >= 45 || slot % 9 == 0 || slot % 9 == 8;
            inventory.setItem(slot, isBorder ? border : panel);
        }
    }

    protected @NotNull ItemStack backItem() {
        return createItem(
            Material.ARROW,
            Component.text("戻る", NamedTextColor.WHITE),
            List.of(Component.text("前の画面へ戻る", NamedTextColor.GRAY))
        );
    }

    protected @NotNull ItemStack closeItem() {
        return createItem(
            Material.BARRIER,
            Component.text("閉じる", NamedTextColor.RED),
            List.of(Component.text("メニューを閉じる", NamedTextColor.GRAY))
        );
    }

    protected @NotNull ItemStack createItem(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }
}
