package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class EquipmentDurabilityDamageScanTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 4. 装備耐久値 > ### 装備中防具・アクセサリの破損通知
     * 検証契約: 防具・アクセサリの現在耐久値が最大値未満なら対象とし、満タン・武器・重複個体は除外する。
     */
    @Test
    void findsDamagedArmorAndAccessoryOnlyOnce() {
        InventoryService inventoryService = mock(InventoryService.class);
        ItemService itemService = mock(ItemService.class);
        EquipmentDurabilityService service = new EquipmentDurabilityService(inventoryService, itemService);
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        Player bukkitPlayer = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getAccount()).thenReturn(account);
        when(player.getBukkit()).thenReturn(bukkitPlayer);
        when(bukkitPlayer.getInventory()).thenReturn(inventory);

        ItemStack damagedHelmet = stack();
        ItemStack fullChestplate = stack();
        ItemStack damagedAccessory = stack();
        ItemStack weapon = stack();
        when(inventory.getHelmet()).thenReturn(damagedHelmet);
        when(inventory.getChestplate()).thenReturn(fullChestplate);
        when(inventory.getLeggings()).thenReturn(null);
        when(inventory.getBoots()).thenReturn(null);
        when(inventoryService.getEquippedAccessorySnapshotItems(player)).thenReturn(
            List.of(damagedAccessory, damagedHelmet, weapon)
        );

        ItemModel helmetModel = model("helmet", "&c壊れた兜", ItemEquipmentSlot.HEAD);
        ItemModel chestplateModel = model("chestplate", "&a新品の胸当て", ItemEquipmentSlot.CHEST);
        ItemModel accessoryModel = model("ring", "&e傷ついた指輪", ItemEquipmentSlot.ACCESSORY);
        ItemModel weaponModel = model("sword", "&b武器", ItemEquipmentSlot.WEAPON);
        when(itemService.findLoadedById("helmet")).thenReturn(helmetModel);
        when(itemService.findLoadedById("chestplate")).thenReturn(chestplateModel);
        when(itemService.findLoadedById("ring")).thenReturn(accessoryModel);
        when(itemService.findLoadedById("sword")).thenReturn(weaponModel);
        when(itemService.findLoadedEquipmentInstanceById("helmet-instance"))
            .thenReturn(instance("helmet-instance", "helmet", 50, 0));
        when(itemService.findLoadedEquipmentInstanceById("chestplate-instance"))
            .thenReturn(instance("chestplate-instance", "chestplate", 50, 50));
        when(itemService.findLoadedEquipmentInstanceById("ring-instance"))
            .thenReturn(instance("ring-instance", "ring", 100, 99));
        when(itemService.findLoadedEquipmentInstanceById("sword-instance"))
            .thenReturn(instance("sword-instance", "sword", 100, 1));

        try (MockedStatic<ItemStackFactory> factory = mockStatic(ItemStackFactory.class)) {
            stubStack(factory, damagedHelmet, "helmet", "helmet-instance");
            stubStack(factory, fullChestplate, "chestplate", "chestplate-instance");
            stubStack(factory, damagedAccessory, "ring", "ring-instance");
            stubStack(factory, weapon, "sword", "sword-instance");

            assertEquals(
                List.of("§c壊れた兜", "§e傷ついた指輪"),
                service.getDamagedArmorAndAccessoryDisplayNames(player)
            );
        }
    }

    private static ItemStack stack() {
        ItemStack itemStack = mock(ItemStack.class);
        when(itemStack.getType()).thenReturn(Material.PAPER);
        return itemStack;
    }

    private static ItemModel model(String id, String name, ItemEquipmentSlot slot) {
        ItemModel model = mock(ItemModel.class);
        ItemEquipment equipment = mock(ItemEquipment.class);
        when(model.getId()).thenReturn(id);
        when(model.getName()).thenReturn(name);
        when(model.getEquipment()).thenReturn(equipment);
        when(equipment.getSlot()).thenReturn(slot);
        return model;
    }

    private static EquipmentInstance instance(String id, String itemId, int max, int value) {
        return new EquipmentInstance(
            id,
            UUID.randomUUID().toString(),
            itemId,
            0,
            0,
            0,
            max,
            value,
            "2026-09-05T00:00:00Z",
            "2026-09-05T00:00:00Z",
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static void stubStack(
        MockedStatic<ItemStackFactory> factory,
        ItemStack itemStack,
        String itemId,
        String instanceId
    ) {
        factory.when(() -> ItemStackFactory.getAstralItemId(itemStack)).thenReturn(itemId);
        factory.when(() -> ItemStackFactory.getCategory(itemStack)).thenReturn("EQUIPMENT");
        factory.when(() -> ItemStackFactory.getEquipmentInstanceId(itemStack)).thenReturn(instanceId);
    }
}
