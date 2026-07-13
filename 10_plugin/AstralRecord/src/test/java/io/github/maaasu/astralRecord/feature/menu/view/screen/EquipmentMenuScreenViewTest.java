package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.inventory.model.AccessorySlotType;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class EquipmentMenuScreenViewTest extends MockBukkitTestBase {

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

        assertMaterial(inventory, EquipmentMenuScreenView.PLAYER_STATUS_SLOT, Material.PLAYER_HEAD);
        assertMaterial(inventory, EquipmentMenuScreenView.EQUIPMENT_BACK_SLOT, Material.SADDLE);
        assertMaterial(inventory, EquipmentMenuScreenView.EQUIPMENT_MAIN_HAND_SLOT, Material.WOODEN_SWORD);
        assertMaterial(inventory, EquipmentMenuScreenView.EQUIPMENT_HEAD_SLOT, Material.LEATHER_HELMET);
        assertMaterial(inventory, EquipmentMenuScreenView.EQUIPMENT_CHEST_SLOT, Material.LEATHER_CHESTPLATE);
        assertMaterial(inventory, EquipmentMenuScreenView.EQUIPMENT_LEGS_SLOT, Material.LEATHER_LEGGINGS);
        assertMaterial(inventory, EquipmentMenuScreenView.EQUIPMENT_FEET_SLOT, Material.LEATHER_BOOTS);
        assertMaterial(inventory, EquipmentMenuScreenView.EQUIPMENT_OFF_HAND_SLOT, Material.SHIELD);
        assertMaterial(inventory, EquipmentMenuScreenView.MEMORY_1_SLOT, Material.HOPPER);
        assertMaterial(inventory, EquipmentMenuScreenView.MEMORY_2_SLOT, Material.HOPPER);
        assertMaterial(inventory, EquipmentMenuScreenView.EQUIPMENT_AMULET_SLOT, Material.CHEST_MINECART);
        assertMaterial(inventory, EquipmentMenuScreenView.EQUIPMENT_TALISMAN_1_SLOT, Material.FURNACE_MINECART);
        assertMaterial(inventory, EquipmentMenuScreenView.EQUIPMENT_TALISMAN_2_SLOT, Material.FURNACE_MINECART);
        assertMaterial(inventory, EquipmentMenuScreenView.EQUIPMENT_CORE_SLOT, Material.HOPPER_MINECART);
        assertMaterial(inventory, EquipmentMenuScreenView.EQUIPMENT_RELIC_1_SLOT, Material.TNT_MINECART);
        assertMaterial(inventory, EquipmentMenuScreenView.EQUIPMENT_RELIC_2_SLOT, Material.TNT_MINECART);
        assertMaterial(inventory, EquipmentMenuScreenView.EQUIPMENT_CHARM_1_SLOT, Material.MINECART);
        assertMaterial(inventory, EquipmentMenuScreenView.EQUIPMENT_CHARM_2_SLOT, Material.MINECART);
        assertMaterial(inventory, EquipmentMenuScreenView.EQUIPMENT_CHARM_3_SLOT, Material.MINECART);
        assertMaterial(inventory, EquipmentMenuScreenView.GAUGE_LARGE_SLOT, Material.SPAWNER);
        assertMaterial(inventory, EquipmentMenuScreenView.GAUGE_MEDIUM_SLOT, Material.SPAWNER);
        assertMaterial(inventory, EquipmentMenuScreenView.GAUGE_SMALL_SLOT, Material.SPAWNER);
    }

    @Test
    void mapsGuiSlotsToTypedAccessorySlots() {
        EquipmentMenuScreenView view = new EquipmentMenuScreenView(
            new NamespacedKey("astralrecord", "equipment_placeholder_mapping_test")
        );

        assertSame(AccessorySlotType.AMULET, view.getAccessorySlotTypeAtSlot(23));
        assertSame(AccessorySlotType.TALISMAN_1, view.getAccessorySlotTypeAtSlot(31));
        assertSame(AccessorySlotType.TALISMAN_2, view.getAccessorySlotTypeAtSlot(32));
        assertSame(AccessorySlotType.CORE, view.getAccessorySlotTypeAtSlot(33));
        assertSame(AccessorySlotType.RELIC_1, view.getAccessorySlotTypeAtSlot(39));
        assertSame(AccessorySlotType.CHARM_1, view.getAccessorySlotTypeAtSlot(40));
        assertSame(AccessorySlotType.CHARM_2, view.getAccessorySlotTypeAtSlot(41));
        assertSame(AccessorySlotType.CHARM_3, view.getAccessorySlotTypeAtSlot(42));
        assertSame(AccessorySlotType.RELIC_2, view.getAccessorySlotTypeAtSlot(43));
    }

    @Test
    void selectsEmptyAccessorySlotOnlyFromSameCategory() {
        var player = server().addPlayer();
        Inventory inventory = Bukkit.createInventory(null, BaseMenuScreenView.SIZE);
        EquipmentMenuScreenView view = new EquipmentMenuScreenView(
            new NamespacedKey("astralrecord", "equipment_placeholder_empty_test")
        );
        view.render(inventory, player, new ItemStack[AccessorySlotType.RELIC_2.getSlotIndex() + 1]);

        assertEquals(
            EquipmentMenuScreenView.EQUIPMENT_TALISMAN_1_SLOT,
            view.firstEmptyAccessorySlot(inventory, AccessorySlotType.TALISMAN_1)
        );
        inventory.setItem(EquipmentMenuScreenView.EQUIPMENT_TALISMAN_1_SLOT, new ItemStack(Material.DIAMOND));
        assertEquals(
            EquipmentMenuScreenView.EQUIPMENT_TALISMAN_2_SLOT,
            view.firstEmptyAccessorySlot(inventory, AccessorySlotType.TALISMAN_1)
        );
        inventory.setItem(EquipmentMenuScreenView.EQUIPMENT_TALISMAN_2_SLOT, new ItemStack(Material.EMERALD));
        assertEquals(-1, view.firstEmptyAccessorySlot(inventory, AccessorySlotType.TALISMAN_1));
        assertEquals(
            EquipmentMenuScreenView.EQUIPMENT_CHARM_1_SLOT,
            view.firstEmptyAccessorySlot(inventory, AccessorySlotType.CHARM_2)
        );
    }

    private static void assertMaterial(Inventory inventory, int slot, Material expected) {
        assertEquals(expected, inventory.getItem(slot).getType());
    }
}
