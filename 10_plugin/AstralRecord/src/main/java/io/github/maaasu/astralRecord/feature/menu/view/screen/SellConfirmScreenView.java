package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class SellConfirmScreenView extends BaseMenuScreenView {
    public static final Component CONFIRM_MESSAGE = Component.text("売却しますか？", NamedTextColor.YELLOW);
    public static final int PREVIOUS_SLOT = ConfirmDialogView.CANCEL_SLOT;
    public static final int SELL_SLOT = ConfirmDialogView.CONFIRM_SLOT;
    public static final int RETURN_TO_SELL_SLOT = ConfirmDialogView.CANCEL_SLOT;
    public static final int NEXT_SLOT = ConfirmDialogView.CONFIRM_SLOT;

    private final NamespacedKey contentPlaceholderKey;

    public SellConfirmScreenView(@NotNull NamespacedKey contentPlaceholderKey) {
        this.contentPlaceholderKey = contentPlaceholderKey;
    }

    public void render(@NotNull Inventory inventory, @NotNull List<ItemStack> items, int pageIndex) {
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        inventory.setItem(PREVIOUS_SLOT, createItem(
            Material.SPECTRAL_ARROW,
            Component.text("戻る", NamedTextColor.WHITE),
            List.of(Component.text("売却枠へ戻ります", NamedTextColor.GRAY))
        ));
        inventory.setItem(13, createItem(
            Material.GOLD_INGOT,
            Component.text("売却確認", NamedTextColor.GOLD),
            List.of(Component.text("売却するとアイテムは消費されます", NamedTextColor.GRAY))
        ));
        inventory.setItem(SELL_SLOT, createItem(
            Material.EMERALD,
            Component.text("売却する", NamedTextColor.GREEN),
            List.of(Component.text("売却額をゴールドとして受け取ります", NamedTextColor.GRAY))
        ));
    }

    public boolean isContentPlaceholder(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() != Material.GRAY_STAINED_GLASS_PANE || !itemStack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = itemStack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(contentPlaceholderKey, PersistentDataType.INTEGER);
    }

    private void fill(@NotNull Inventory inventory, @NotNull Material material) {
        ItemStack itemStack = createItem(material, Component.text(" "), List.of());
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(contentPlaceholderKey, PersistentDataType.INTEGER, 1);
            itemStack.setItemMeta(meta);
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, itemStack);
        }
    }
}
