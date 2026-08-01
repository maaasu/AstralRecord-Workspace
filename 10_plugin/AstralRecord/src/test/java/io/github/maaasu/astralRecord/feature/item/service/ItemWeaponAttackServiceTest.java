package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItemWeaponAttackServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 武器左クリック処理
     * 検証契約: 主手に weapon がない場合、スキル発動用の武器として扱わない。
     */
    @Test
    void hasUsableMainHandWeaponRejectsMissingWeapon() {
        InventoryService inventoryService = mock(InventoryService.class);
        AstPlayer player = mock(AstPlayer.class);
        ItemWeaponAttackService service = new ItemWeaponAttackService(inventoryService, mock(SkillService.class));

        assertFalse(service.hasUsableMainHandWeapon(player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 武器左クリック処理
     * 検証契約: 耐久値切れの主手 weapon はスキル発動用の武器として扱わない。
     */
    @Test
    void hasUsableMainHandWeaponRejectsBrokenWeapon() {
        InventoryService inventoryService = mock(InventoryService.class);
        AstPlayer player = mock(AstPlayer.class);
        ItemModel weapon = weaponModel();
        EquipmentDurabilityService durabilityService = mock(EquipmentDurabilityService.class);
        when(inventoryService.getItemModelInHand(player, EquipmentSlot.HAND)).thenReturn(weapon);
        when(durabilityService.canUseMainHandWeapon(player)).thenReturn(false);
        ItemWeaponAttackService service = new ItemWeaponAttackService(inventoryService, mock(SkillService.class));
        service.setEquipmentDurabilityService(durabilityService);

        assertFalse(service.hasUsableMainHandWeapon(player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 武器左クリック処理
     * 検証契約: 使用可能な主手 weapon はスキル発動用の武器として認める。
     */
    @Test
    void hasUsableMainHandWeaponAcceptsIntactWeapon() {
        InventoryService inventoryService = mock(InventoryService.class);
        AstPlayer player = mock(AstPlayer.class);
        ItemModel weapon = weaponModel();
        EquipmentDurabilityService durabilityService = mock(EquipmentDurabilityService.class);
        when(inventoryService.getItemModelInHand(player, EquipmentSlot.HAND)).thenReturn(weapon);
        when(durabilityService.canUseMainHandWeapon(player)).thenReturn(true);
        ItemWeaponAttackService service = new ItemWeaponAttackService(inventoryService, mock(SkillService.class));
        service.setEquipmentDurabilityService(durabilityService);

        assertTrue(service.hasUsableMainHandWeapon(player));
    }

    private ItemModel weaponModel() {
        ItemModel model = mock(ItemModel.class);
        ItemEquipment equipment = mock(ItemEquipment.class);
        when(model.getEquipment()).thenReturn(equipment);
        when(equipment.getSlot()).thenReturn(ItemEquipmentSlot.WEAPON);
        return model;
    }
}
