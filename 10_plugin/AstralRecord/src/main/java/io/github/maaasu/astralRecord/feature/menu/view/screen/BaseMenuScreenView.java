package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public abstract class BaseMenuScreenView {
    public static final int SIZE = 54;
    public static final int BACK_SLOT = 49;
    public static final String DISPLAY_AMOUNT_LORE_PREFIX = "スタック: ";

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
            Material.SPECTRAL_ARROW,
            Component.text("戻る", NamedTextColor.WHITE, TextDecoration.BOLD),
            List.of(
                Component.text("前の画面へ戻ります", NamedTextColor.GRAY),
                Component.text("navigation", NamedTextColor.DARK_GRAY)
            )
        );
    }

    protected @NotNull ItemStack createItem(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        return GuiItems.create(material, name, lore);
    }

    protected @NotNull ItemStack cloneWithAmountLore(@NotNull ItemStack itemStack) {
        ItemStack displayItem = itemStack.clone();
        if (displayItem.getMaxStackSize() <= 1 && displayItem.getAmount() <= 1) {
            return displayItem;
        }

        ItemMeta meta = displayItem.getItemMeta();
        if (meta == null) {
            return displayItem;
        }

        List<Component> lore = meta.hasLore() && meta.lore() != null
            ? new ArrayList<>(meta.lore())
            : new ArrayList<>();
        lore.add(Component.text(DISPLAY_AMOUNT_LORE_PREFIX + displayItem.getAmount(), NamedTextColor.GRAY));
        meta.lore(lore.stream().map(GuiItems::noItalic).toList());
        displayItem.setItemMeta(meta);
        return displayItem;
    }
}
