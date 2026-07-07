package io.github.maaasu.astralRecord.feature.menu.view.screen;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class EquipmentRepairMenuScreenView extends BaseMenuScreenView {
    public static final int GUIDE_SLOT = 11;
    public static final int INFO_SLOT = 15;
    public static final int TARGET_SLOT = 20;
    public static final int COST_SLOT = 22;
    public static final int EXECUTE_SLOT = 24;

    public void render(
        @NotNull Inventory inventory,
        @Nullable ItemStack selectedEquipment,
        @NotNull ItemStack costItem,
        @NotNull ItemStack guideItem,
        @NotNull ItemStack infoItem,
        @NotNull ItemStack executeItem
    ) {
        fill(inventory);
        inventory.setItem(GUIDE_SLOT, guideItem);
        inventory.setItem(INFO_SLOT, infoItem);
        inventory.setItem(TARGET_SLOT, selectedEquipment != null ? selectedEquipment : targetPlaceholder());
        inventory.setItem(COST_SLOT, costItem);
        inventory.setItem(EXECUTE_SLOT, executeItem);
    }

    private @NotNull ItemStack targetPlaceholder() {
        return createItem(
            Material.ARMOR_STAND,
            Component.text("修理する装備", NamedTextColor.YELLOW),
            List.of(
                Component.text("下の所持品から装備をクリックしてセットします。", NamedTextColor.GRAY),
                Component.text("セット済みの装備をクリックすると取り外します。", NamedTextColor.GRAY)
            )
        );
    }

    public @NotNull ItemStack createGuideItem() {
        return createItem(
            Material.ANVIL,
            Component.text("修理ガイド", NamedTextColor.GOLD, TextDecoration.BOLD),
            List.of(
                Component.text("1. 修理したい装備を下の所持品から選びます。", NamedTextColor.GRAY),
                Component.text("2. 不足耐久と必要ゴールドを確認します。", NamedTextColor.GRAY),
                Component.text("3. 実行すると耐久値を最大まで回復します。", NamedTextColor.GRAY)
            )
        );
    }
}
