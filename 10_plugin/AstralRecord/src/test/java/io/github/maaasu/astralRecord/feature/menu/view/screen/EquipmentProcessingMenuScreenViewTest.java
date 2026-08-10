package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentProcessingMode;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EquipmentProcessingMenuScreenViewTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 5.5. 装備加工画面
     * 検証契約: 修理・強化タブ、常時見えるモード識別、素材一覧導線、必要ゴールドを統合した実行枠を共通GUIへ描画する。
     */
    @Test
    void rendersModeIdentityAndMaterialListEntryWithActualMaterialIcons() {
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
            new ItemStack(Material.CHEST),
            new ItemStack(Material.LIME_CONCRETE)
        );

        assertEquals("装備加工｜強化", view.processingTitle(EquipmentProcessingMode.ENHANCEMENT));
        assertEquals(Material.PURPLE_STAINED_GLASS_PANE, inventory.getItem(1).getType());
        assertEquals(Material.ENCHANTING_TABLE, inventory.getItem(EquipmentProcessingMenuScreenView.MODE_EMBLEM_SLOT).getType());
        assertEquals(Material.BOOK, inventory.getItem(EquipmentProcessingMenuScreenView.GUIDE_SLOT).getType());
        assertEquals(Material.ANVIL, inventory.getItem(EquipmentProcessingMenuScreenView.REPAIR_TAB_SLOT).getType());
        assertEquals(Material.ENCHANTING_TABLE, inventory.getItem(EquipmentProcessingMenuScreenView.ENHANCEMENT_TAB_SLOT).getType());
        assertEquals(Material.NETHER_STAR, inventory.getItem(EquipmentProcessingMenuScreenView.INFO_SLOT).getType());
        assertEquals(Material.DIAMOND, inventory.getItem(EquipmentProcessingMenuScreenView.MATERIAL_START_SLOT).getType());
        assertEquals(24, EquipmentProcessingMenuScreenView.EXECUTE_SLOT);
        assertEquals(Material.LIME_CONCRETE, inventory.getItem(EquipmentProcessingMenuScreenView.EXECUTE_SLOT).getType());
        assertEquals(Material.CHEST, inventory.getItem(EquipmentProcessingMenuScreenView.MATERIAL_LIST_SLOT).getType());

        view.render(
            inventory,
            EquipmentProcessingMode.REPAIR,
            null,
            new ItemStack(Material.BOOK),
            new ItemStack(Material.SPYGLASS),
            List.of(),
            new ItemStack(Material.GOLD_INGOT),
            new ItemStack(Material.BARRIER)
        );

        assertEquals("装備加工｜修理", view.processingTitle(EquipmentProcessingMode.REPAIR));
        assertEquals(Material.LIME_STAINED_GLASS_PANE, inventory.getItem(1).getType());
        assertEquals(Material.ANVIL, inventory.getItem(EquipmentProcessingMenuScreenView.MODE_EMBLEM_SLOT).getType());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(EquipmentProcessingMenuScreenView.MATERIAL_START_SLOT).getType());
        assertEquals(Material.SPYGLASS, inventory.getItem(EquipmentProcessingMenuScreenView.INFO_SLOT).getType());
        assertEquals(Material.BARRIER, inventory.getItem(EquipmentProcessingMenuScreenView.EXECUTE_SLOT).getType());
        assertEquals(Material.GOLD_INGOT, inventory.getItem(EquipmentProcessingMenuScreenView.MATERIAL_LIST_SLOT).getType());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 5.5. 装備加工画面
     * 検証契約: 必要素材一覧は45種類単位で実アイテムをページ表示し、戻る・前後ページ枠を表示する。
     */
    @Test
    void rendersAllMaterialsInPagedMaterialList() {
        Inventory inventory = Bukkit.createInventory(null, BaseMenuScreenView.SIZE);
        EquipmentProcessingMenuScreenView view = new EquipmentProcessingMenuScreenView();
        List<ItemStack> materials = Collections.nCopies(46, new ItemStack(Material.DIAMOND));

        view.renderMaterialList(inventory, materials, 0, 2);

        assertEquals("装備加工｜強化｜必要素材一覧", view.materialListTitle());
        assertEquals(Material.DIAMOND, inventory.getItem(0).getType());
        assertEquals(Material.MAP, inventory.getItem(EquipmentProcessingMenuScreenView.MATERIAL_LIST_NEXT_SLOT).getType());

        view.renderMaterialList(inventory, materials, 1, 2);

        assertEquals(Material.DIAMOND, inventory.getItem(0).getType());
        assertEquals(Material.MAP, inventory.getItem(EquipmentProcessingMenuScreenView.MATERIAL_LIST_PREVIOUS_SLOT).getType());
        assertEquals(Material.SPECTRAL_ARROW, inventory.getItem(EquipmentProcessingMenuScreenView.MATERIAL_LIST_BACK_SLOT).getType());
    }
}
