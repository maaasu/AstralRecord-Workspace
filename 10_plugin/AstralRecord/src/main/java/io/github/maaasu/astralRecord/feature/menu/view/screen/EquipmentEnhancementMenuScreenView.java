package io.github.maaasu.astralRecord.feature.menu.view.screen;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class EquipmentEnhancementMenuScreenView extends BaseMenuScreenView {
    public static final int GUIDE_SLOT = 11;
    public static final int INFO_SLOT = 15;
    public static final int TARGET_SLOT = 20;
    public static final int EXECUTE_SLOT = 24;
    public static final int[] MATERIAL_SLOTS = {22, 23, 30, 31, 32, 39};

    public void render(
        @NotNull Inventory inventory,
        @Nullable ItemStack selectedWeapon,
        @NotNull List<ItemStack> materialItems,
        @NotNull ItemStack guideItem,
        @NotNull ItemStack infoItem,
        @NotNull ItemStack executeItem
    ) {
        fill(inventory);
        inventory.setItem(GUIDE_SLOT, guideItem);
        inventory.setItem(INFO_SLOT, infoItem);
        inventory.setItem(TARGET_SLOT, selectedWeapon != null ? selectedWeapon : targetPlaceholder());

        for (int index = 0; index < MATERIAL_SLOTS.length; index++) {
            ItemStack display = index < materialItems.size() ? materialItems.get(index) : materialPlaceholder();
            inventory.setItem(MATERIAL_SLOTS[index], display);
        }

        inventory.setItem(EXECUTE_SLOT, executeItem);
        inventory.setItem(BACK_SLOT, backItem());
        inventory.setItem(CLOSE_SLOT, closeItem());
    }

    private @NotNull ItemStack targetPlaceholder() {
        return createItem(
            Material.NETHERITE_SWORD,
            Component.text("強化する武器", NamedTextColor.YELLOW),
            List.of(
                Component.text("下のインベントリから武器をクリックしてセットします。", NamedTextColor.GRAY),
                Component.text("セット済みの武器をクリックすると取り外せます。", NamedTextColor.GRAY)
            )
        );
    }

    private @NotNull ItemStack materialPlaceholder() {
        return createItem(
            Material.GRAY_STAINED_GLASS_PANE,
            Component.text("必要素材", NamedTextColor.DARK_GRAY),
            List.of(Component.text("武器をセットすると必要素材が表示されます。", NamedTextColor.GRAY))
        );
    }
}
