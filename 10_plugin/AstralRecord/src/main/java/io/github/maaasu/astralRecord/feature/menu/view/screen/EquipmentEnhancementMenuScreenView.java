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
    public static final int MATERIAL_SLOT = 22;
    public static final int EXECUTE_SLOT = 24;

    /**
     * 装備強化 GUI の上部表示を描画します。
     *
     * @param inventory 描画対象の 54 スロット GUI
     * @param selectedEquipment 強化対象としてセット済みの装備。未選択なら placeholder を表示する
     * @param materialItem 消費アイテム一覧を lore に持つ表示専用アイテム
     * @param guideItem 操作ガイドアイテム
     * @param infoItem 強化値・成功率・必要ゴールドなどの情報アイテム
     * @param executeItem 強化実行アイテム
     */
    public void render(
        @NotNull Inventory inventory,
        @Nullable ItemStack selectedEquipment,
        @NotNull ItemStack materialItem,
        @NotNull ItemStack guideItem,
        @NotNull ItemStack infoItem,
        @NotNull ItemStack executeItem
    ) {
        fill(inventory);
        inventory.setItem(GUIDE_SLOT, guideItem);
        inventory.setItem(INFO_SLOT, infoItem);
        inventory.setItem(TARGET_SLOT, selectedEquipment != null ? selectedEquipment : targetPlaceholder());
        inventory.setItem(MATERIAL_SLOT, materialItem);
        inventory.setItem(EXECUTE_SLOT, executeItem);
        inventory.setItem(BACK_SLOT, backItem());
    }

    private @NotNull ItemStack targetPlaceholder() {
        return createItem(
            Material.ARMOR_STAND,
            Component.text("強化する装備", NamedTextColor.YELLOW),
            List.of(
                Component.text("下のインベントリから装備をクリックしてセットします。", NamedTextColor.GRAY),
                Component.text("セット済みの装備をクリックすると取り外せます。", NamedTextColor.GRAY)
            )
        );
    }

}
