package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentProcessingMode;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EquipmentProcessingMenuScreenViewTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 5.5. 装備加工画面
     * 検証契約: 修理・強化タブ、情報、対象、素材、必要ゴールドを統合した実行枠を共通GUIへ描画する。
     */
    @Test
    void rendersRepairAndEnhancementTabsWithActualMaterialIcons() {
        Inventory inventory = Bukkit.createInventory(null, BaseMenuScreenView.SIZE);
        EquipmentProcessingMenuScreenView view = new EquipmentProcessingMenuScreenView();
        ItemStack material = new ItemStack(Material.DIAMOND);

        view.render(
            inventory,
            EquipmentProcessingMode.ENHANCEMENT,
            null,
            new ItemStack(Material.BOOK),
            new ItemStack(Material.NETHER_STAR),
            List.of(material),
            new ItemStack(Material.LIME_CONCRETE)
        );

        assertEquals(Material.BOOK, inventory.getItem(EquipmentProcessingMenuScreenView.GUIDE_SLOT).getType());
        assertEquals(Material.ANVIL, inventory.getItem(EquipmentProcessingMenuScreenView.REPAIR_TAB_SLOT).getType());
        assertEquals(Material.ENCHANTING_TABLE, inventory.getItem(EquipmentProcessingMenuScreenView.ENHANCEMENT_TAB_SLOT).getType());
        assertEquals(Material.NETHER_STAR, inventory.getItem(EquipmentProcessingMenuScreenView.INFO_SLOT).getType());
        assertEquals(Material.DIAMOND, inventory.getItem(EquipmentProcessingMenuScreenView.MATERIAL_START_SLOT).getType());
        assertEquals(24, EquipmentProcessingMenuScreenView.EXECUTE_SLOT);
        assertEquals(Material.LIME_CONCRETE, inventory.getItem(EquipmentProcessingMenuScreenView.EXECUTE_SLOT).getType());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(25).getType());

        view.render(
            inventory,
            EquipmentProcessingMode.REPAIR,
            null,
            new ItemStack(Material.BOOK),
            new ItemStack(Material.SPYGLASS),
            List.of(),
            new ItemStack(Material.BARRIER)
        );

        assertEquals(Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(EquipmentProcessingMenuScreenView.MATERIAL_START_SLOT).getType());
        assertEquals(Material.SPYGLASS, inventory.getItem(EquipmentProcessingMenuScreenView.INFO_SLOT).getType());
        assertEquals(Material.BARRIER, inventory.getItem(EquipmentProcessingMenuScreenView.EXECUTE_SLOT).getType());
    }
}
