package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.inventory.model.AccessorySlotType;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class EquipmentMenuScreenViewTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 5. 画面固有の描画不変条件
     * 検証契約: 54 slot装備画面へstatus・pet・back・選択main hand・armor・off hand・memory・accessory・gaugeを設計slotとmarker Materialで描画する。
     */
    @Test
    void rendersRequestedVanillaLayoutAndSelectedHotbarItem() {
        var player = server().addPlayer();
        player.getInventory().setHeldItemSlot(2);
        player.getInventory().setItem(2, new ItemStack(Material.WOODEN_SWORD));
        Inventory inventory = Bukkit.createInventory(null, BaseMenuScreenView.SIZE);
        EquipmentMenuScreenView view = new EquipmentMenuScreenView(
            new NamespacedKey("astralrecord", "equipment_placeholder_test")
        );

        view.render(inventory, player, new ItemStack[AccessorySlotType.RELIC_2.getSlotIndex() + 1]);

        assertEquals(54, inventory.getSize());
        assertMaterial(inventory, 0, Material.PLAYER_HEAD);
        assertMaterial(inventory, 16, Material.SADDLE);
        assertMaterial(inventory, 49, Material.SPECTRAL_ARROW);
        assertMaterial(inventory, 19, Material.WOODEN_SWORD);
        assertMaterial(inventory, 11, Material.LEATHER_HELMET);
        assertMaterial(inventory, 20, Material.LEATHER_CHESTPLATE);
        assertMaterial(inventory, 29, Material.LEATHER_LEGGINGS);
        assertMaterial(inventory, 38, Material.LEATHER_BOOTS);
        assertMaterial(inventory, 21, Material.GLOW_ITEM_FRAME);
        assertDarkGrayLeatherArmor(inventory, 11);
        assertDarkGrayLeatherArmor(inventory, 20);
        assertDarkGrayLeatherArmor(inventory, 29);
        assertDarkGrayLeatherArmor(inventory, 38);
        assertMaterial(inventory, 27, Material.HOPPER);
        assertMaterial(inventory, 36, Material.HOPPER);
        assertMaterial(inventory, 23, Material.CHEST_MINECART);
        assertMaterial(inventory, 31, Material.FURNACE_MINECART);
        assertMaterial(inventory, 33, Material.FURNACE_MINECART);
        assertMaterial(inventory, 32, Material.HOPPER_MINECART);
        assertMaterial(inventory, 39, Material.TNT_MINECART);
        assertMaterial(inventory, 43, Material.TNT_MINECART);
        assertMaterial(inventory, 40, Material.MINECART);
        assertMaterial(inventory, 41, Material.MINECART);
        assertMaterial(inventory, 42, Material.MINECART);
        assertMaterial(inventory, 26, Material.SPAWNER);
        assertMaterial(inventory, 35, Material.SPAWNER);
        assertMaterial(inventory, 44, Material.SPAWNER);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 5. 画面固有の描画不変条件
     * 検証契約: main/off hand空slotに各専用empty markerを描画する。
     */
    @Test
    void usesDedicatedEmptyMarkersForMainAndOffHandSlots() {
        var player = server().addPlayer();
        Inventory inventory = Bukkit.createInventory(null, BaseMenuScreenView.SIZE);
        EquipmentMenuScreenView view = new EquipmentMenuScreenView(
            new NamespacedKey("astralrecord", "equipment_placeholder_hand_test")
        );

        view.render(inventory, player, new ItemStack[AccessorySlotType.RELIC_2.getSlotIndex() + 1]);

        assertMaterial(inventory, 19, Material.ITEM_FRAME);
        assertMaterial(inventory, 21, Material.GLOW_ITEM_FRAME);
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        view.render(inventory, player, new ItemStack[AccessorySlotType.RELIC_2.getSlotIndex() + 1]);
        assertMaterial(inventory, 21, Material.SHIELD);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 5. 画面固有の描画不変条件
     * 検証契約: GUI slot 23/31/33/32/39/43/40..42をAMULET/TALISMAN_1..2/CORE/RELIC_1..2/CHARM_1..3へ対応付ける。
     */
    @Test
    void mapsGuiSlotsToTypedAccessorySlots() {
        EquipmentMenuScreenView view = new EquipmentMenuScreenView(
            new NamespacedKey("astralrecord", "equipment_placeholder_mapping_test")
        );

        assertSame(AccessorySlotType.AMULET, view.getAccessorySlotTypeAtSlot(23));
        assertSame(AccessorySlotType.TALISMAN_1, view.getAccessorySlotTypeAtSlot(31));
        assertSame(AccessorySlotType.CORE, view.getAccessorySlotTypeAtSlot(32));
        assertSame(AccessorySlotType.TALISMAN_2, view.getAccessorySlotTypeAtSlot(33));
        assertSame(AccessorySlotType.RELIC_1, view.getAccessorySlotTypeAtSlot(39));
        assertSame(AccessorySlotType.CHARM_1, view.getAccessorySlotTypeAtSlot(40));
        assertSame(AccessorySlotType.CHARM_2, view.getAccessorySlotTypeAtSlot(41));
        assertSame(AccessorySlotType.CHARM_3, view.getAccessorySlotTypeAtSlot(42));
        assertSame(AccessorySlotType.RELIC_2, view.getAccessorySlotTypeAtSlot(43));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 5. 画面固有の描画不変条件
     * 検証契約: 空accessory選択先を同一equipment tagカテゴリ内だけから選ぶ。
     */
    @Test
    void selectsEmptyAccessorySlotOnlyFromSameCategory() {
        var player = server().addPlayer();
        Inventory inventory = Bukkit.createInventory(null, BaseMenuScreenView.SIZE);
        EquipmentMenuScreenView view = new EquipmentMenuScreenView(
            new NamespacedKey("astralrecord", "equipment_placeholder_empty_test")
        );
        view.render(inventory, player, new ItemStack[AccessorySlotType.RELIC_2.getSlotIndex() + 1]);

        assertEquals(
            31,
            view.firstEmptyAccessorySlot(inventory, AccessorySlotType.TALISMAN_1)
        );
        inventory.setItem(31, new ItemStack(Material.DIAMOND));
        assertEquals(
            33,
            view.firstEmptyAccessorySlot(inventory, AccessorySlotType.TALISMAN_1)
        );
        inventory.setItem(33, new ItemStack(Material.EMERALD));
        assertEquals(-1, view.firstEmptyAccessorySlot(inventory, AccessorySlotType.TALISMAN_1));
        assertEquals(
            40,
            view.firstEmptyAccessorySlot(inventory, AccessorySlotType.CHARM_2)
        );
    }

    private static void assertMaterial(Inventory inventory, int slot, Material expected) {
        assertEquals(expected, inventory.getItem(slot).getType());
    }

    private static void assertDarkGrayLeatherArmor(Inventory inventory, int slot) {
        LeatherArmorMeta meta = (LeatherArmorMeta) inventory.getItem(slot).getItemMeta();
        assertEquals(48, meta.getColor().getRed());
        assertEquals(48, meta.getColor().getGreen());
        assertEquals(48, meta.getColor().getBlue());
    }
}
