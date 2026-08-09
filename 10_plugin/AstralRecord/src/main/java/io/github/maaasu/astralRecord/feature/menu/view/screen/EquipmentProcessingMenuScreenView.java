package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentProcessingMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 修理・強化が共有する装備加工 GUI の表示シェルです。 */
public final class EquipmentProcessingMenuScreenView extends BaseMenuScreenView {
    public static final int GUIDE_SLOT = 10;
    public static final int REPAIR_TAB_SLOT = 12;
    public static final int ENHANCEMENT_TAB_SLOT = 14;
    public static final int INFO_SLOT = 16;
    public static final int TARGET_SLOT = 20;
    public static final int MATERIAL_START_SLOT = 22;
    public static final int MATERIAL_SLOT_COUNT = 3;
    public static final int GOLD_SLOT = 25;
    public static final int EXECUTE_SLOT = 26;

    /** 共通の加工画面を描画します。素材は実アイテムを最大3枠で表示します。 */
    public void render(
        @NotNull Inventory inventory,
        @NotNull EquipmentProcessingMode mode,
        @Nullable ItemStack selectedEquipment,
        @NotNull ItemStack guideItem,
        @NotNull ItemStack infoItem,
        @NotNull List<ItemStack> materialItems,
        @NotNull ItemStack goldItem,
        @NotNull ItemStack executeItem
    ) {
        fill(inventory);
        inventory.setItem(GUIDE_SLOT, guideItem);
        inventory.setItem(REPAIR_TAB_SLOT, tabItem(EquipmentProcessingMode.REPAIR, mode));
        inventory.setItem(ENHANCEMENT_TAB_SLOT, tabItem(EquipmentProcessingMode.ENHANCEMENT, mode));
        inventory.setItem(INFO_SLOT, infoItem);
        inventory.setItem(TARGET_SLOT, selectedEquipment == null ? targetPlaceholder(mode) : selectedEquipment);
        for (int index = 0; index < MATERIAL_SLOT_COUNT; index++) {
            inventory.setItem(MATERIAL_START_SLOT + index,
                index < materialItems.size() ? materialItems.get(index) : emptyPanel());
        }
        inventory.setItem(GOLD_SLOT, goldItem);
        inventory.setItem(EXECUTE_SLOT, executeItem);
    }

    private @NotNull ItemStack tabItem(
        @NotNull EquipmentProcessingMode tabMode,
        @NotNull EquipmentProcessingMode activeMode
    ) {
        boolean active = tabMode == activeMode;
        Material material = tabMode == EquipmentProcessingMode.REPAIR ? Material.ANVIL : Material.KNOWLEDGE_BOOK;
        String title = tabMode == EquipmentProcessingMode.REPAIR ? "修理モード" : "強化モード";
        return createItem(material, Component.text(title, active ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                TextDecoration.BOLD), List.of(Component.text(
                active ? "現在選択中です。" : "クリックしてモードを切り替えます。", NamedTextColor.GRAY)));
    }

    private @NotNull ItemStack targetPlaceholder(@NotNull EquipmentProcessingMode mode) {
        if (mode == EquipmentProcessingMode.REPAIR) {
            return createItem(Material.ARMOR_STAND, Component.text("修理する装備", NamedTextColor.YELLOW), List.of(
                Component.text("下の装備を通常クリックすると修理内容を確認できます。", NamedTextColor.GRAY),
                Component.text("シフト左クリックで移動せず、その場で修理します。", NamedTextColor.GREEN)
            ));
        }
        String operation = "強化";
        return createItem(Material.ARMOR_STAND, Component.text(operation + "する装備", NamedTextColor.YELLOW), List.of(
            Component.text("下の所持品から装備をクリックしてセットします。", NamedTextColor.GRAY),
            Component.text("セット済みの装備をクリックすると取り外せます。", NamedTextColor.GRAY)
        ));
    }

    private @NotNull ItemStack emptyPanel() {
        return createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
    }
}
