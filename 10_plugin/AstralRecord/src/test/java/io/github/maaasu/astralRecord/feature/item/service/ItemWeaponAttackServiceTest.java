package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import org.bukkit.Location;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        AccountModel account = mock(AccountModel.class);
        EquipmentDurabilityService durabilityService = mock(EquipmentDurabilityService.class);
        when(inventoryService.getItemModelInHand(player, EquipmentSlot.HAND)).thenReturn(weapon);
        when(player.getAccount()).thenReturn(account);
        when(account.getLevel()).thenReturn(1);
        when(durabilityService.canUseMainHandWeapon(player)).thenReturn(false);
        ItemWeaponAttackService service = new ItemWeaponAttackService(inventoryService, mock(SkillService.class));
        service.setEquipmentDurabilityService(durabilityService);

        assertFalse(service.hasUsableMainHandWeapon(player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 武器左クリック処理
     * 検証契約: 必要プレイヤーレベル未満の主手 weapon はスキル発動用の武器として扱わない。
     */
    @Test
    void hasUsableMainHandWeaponRejectsWeaponWhenPlayerLevelIsTooLow() {
        InventoryService inventoryService = mock(InventoryService.class);
        AstPlayer player = mock(AstPlayer.class);
        ItemModel weapon = weaponModel();
        AccountModel account = mock(AccountModel.class);
        when(inventoryService.getItemModelInHand(player, EquipmentSlot.HAND)).thenReturn(weapon);
        when(player.getAccount()).thenReturn(account);
        when(account.getLevel()).thenReturn(1);
        when(weapon.getEquipment().getRequiredLevel()).thenReturn(10);
        ItemWeaponAttackService service = new ItemWeaponAttackService(inventoryService, mock(SkillService.class));

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
        AccountModel account = mock(AccountModel.class);
        EquipmentDurabilityService durabilityService = mock(EquipmentDurabilityService.class);
        when(inventoryService.getItemModelInHand(player, EquipmentSlot.HAND)).thenReturn(weapon);
        when(player.getAccount()).thenReturn(account);
        when(account.getLevel()).thenReturn(1);
        when(durabilityService.canUseMainHandWeapon(player)).thenReturn(true);
        ItemWeaponAttackService service = new ItemWeaponAttackService(inventoryService, mock(SkillService.class));
        service.setEquipmentDurabilityService(durabilityService);

        assertTrue(service.hasUsableMainHandWeapon(player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 武器左クリック処理
     * 検証契約: 通常攻撃は装備マスタの8武器タグから対応するシステムスキルを決定する。
     */
    @Test
    void currentLeftClickSkillIdResolvesSystemAttackFromWeaponTag() {
        InventoryService inventoryService = mock(InventoryService.class);
        AstPlayer player = mock(AstPlayer.class);
        ItemModel sword = weaponModel("SWORD");
        ItemModel hammer = weaponModel("hammer");
        ItemModel spear = weaponModel("Spear");
        ItemModel bow = weaponModel("BOW");
        ItemModel shortbow = weaponModel("shortbow");
        ItemModel longbow = weaponModel("LongBow");
        ItemModel wand = weaponModel("wand");
        ItemModel staff = weaponModel("STAFF");
        when(inventoryService.getItemModelInHand(player, EquipmentSlot.HAND)).thenReturn(
            sword,
            hammer,
            spear,
            bow,
            shortbow,
            longbow,
            wand,
            staff
        );
        ItemWeaponAttackService service = new ItemWeaponAttackService(inventoryService, mock(SkillService.class));

        assertEquals(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MELEE, service.currentLeftClickSkillId(player));
        assertEquals(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_HAMMER, service.currentLeftClickSkillId(player));
        assertEquals(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_SPEAR, service.currentLeftClickSkillId(player));
        assertEquals(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_BOW, service.currentLeftClickSkillId(player));
        assertEquals(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_SHORTBOW, service.currentLeftClickSkillId(player));
        assertEquals(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_LONGBOW, service.currentLeftClickSkillId(player));
        assertEquals(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_WAND, service.currentLeftClickSkillId(player));
        assertEquals(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MAGIC, service.currentLeftClickSkillId(player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 武器左クリック処理
     * 検証契約: 8武器種は1つの通常攻撃cooldownを共有し、成功時は武器別の基本tickで開始する。
     */
    @Test
    void normalAttacksShareCooldownAndUseWeaponSpecificBaseTicks() {
        InventoryService inventoryService = mock(InventoryService.class);
        SkillService skillService = mock(SkillService.class);
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(player.getAccount()).thenReturn(account);
        when(account.getLevel()).thenReturn(1);
        ItemModel sword = weaponModel("SWORD");
        ItemModel hammer = weaponModel("HAMMER");
        ItemModel spear = weaponModel("SPEAR");
        ItemModel bow = weaponModel("BOW");
        ItemModel shortbow = weaponModel("SHORTBOW");
        ItemModel longbow = weaponModel("LONGBOW");
        ItemModel wand = weaponModel("WAND");
        ItemModel staff = weaponModel("STAFF");
        when(inventoryService.getItemModelInHand(player, EquipmentSlot.HAND)).thenReturn(
            sword,
            hammer,
            spear,
            bow,
            shortbow,
            longbow,
            wand,
            staff
        );
        when(skillService.isOnCooldown(any(), eq(SkillService.WEAPON_NORMAL_ATTACK_COOLDOWN_ID)))
            .thenReturn(false);
        when(skillService.castSkill(any(), anyString(), eq(SkillCastTrigger.AUTO_ATTACK),
            any(Location.class), isNull(), any())).thenReturn(SkillCastResult.succeeded());
        ItemWeaponAttackService service = new ItemWeaponAttackService(inventoryService, skillService);
        Location location = new Location(null, 0.0D, 0.0D, 0.0D);

        for (int attack = 0; attack < 8; attack++) {
            service.handleLeftClick(player, location);
        }

        verify(skillService, times(8)).isOnCooldown(any(), eq(SkillService.WEAPON_NORMAL_ATTACK_COOLDOWN_ID));
        verify(skillService).startAttackCooldown(any(), eq(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MELEE), eq(10L));
        verify(skillService).startAttackCooldown(any(), eq(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_HAMMER), eq(22L));
        verify(skillService).startAttackCooldown(any(), eq(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_SPEAR), eq(14L));
        verify(skillService).startAttackCooldown(any(), eq(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_BOW), eq(12L));
        verify(skillService).startAttackCooldown(any(), eq(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_SHORTBOW), eq(7L));
        verify(skillService).startAttackCooldown(any(), eq(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_LONGBOW), eq(17L));
        verify(skillService).startAttackCooldown(any(), eq(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_WAND), eq(9L));
        verify(skillService).startAttackCooldown(any(), eq(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MAGIC), eq(14L));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 武器左クリック処理
     * 検証契約: 通常攻撃の試行通知は、通常攻撃executorの成功・失敗にかかわらず呼び出される。
     */
    @Test
    void attackAttemptListenerRunsForSuccessfulAndFailedNormalAttack() {
        InventoryService inventoryService = mock(InventoryService.class);
        SkillService skillService = mock(SkillService.class);
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        ItemModel weapon = weaponModel("SWORD");
        when(inventoryService.getItemModelInHand(player, EquipmentSlot.HAND)).thenReturn(weapon, weapon);
        when(player.getAccount()).thenReturn(account);
        when(account.getLevel()).thenReturn(1);
        when(skillService.isOnCooldown(any(), anyString())).thenReturn(false);
        when(skillService.castSkill(
                any(),
                eq(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MELEE),
                eq(SkillCastTrigger.AUTO_ATTACK),
                any(Location.class),
                isNull(),
                any()
        )).thenReturn(SkillCastResult.succeeded(), SkillCastResult.failure(null));

        ItemWeaponAttackService service = new ItemWeaponAttackService(inventoryService, skillService);
        AtomicInteger attempts = new AtomicInteger();
        service.setAttackAttemptListener(playerToNotify -> attempts.incrementAndGet());

        service.handleLeftClick(player, new Location(null, 0.0D, 0.0D, 0.0D));
        service.handleLeftClick(player, new Location(null, 0.0D, 0.0D, 0.0D));

        assertEquals(2, attempts.get());
    }

    private ItemModel weaponModel() {
        return weaponModel(null);
    }

    private ItemModel weaponModel(String tag) {
        ItemModel model = mock(ItemModel.class);
        ItemEquipment equipment = mock(ItemEquipment.class);
        when(model.getEquipment()).thenReturn(equipment);
        when(equipment.getSlot()).thenReturn(ItemEquipmentSlot.WEAPON);
        when(equipment.getTag()).thenReturn(tag);
        when(equipment.getRequiredLevel()).thenReturn(0);
        when(equipment.getRequiredClasses()).thenReturn(List.of());
        return model;
    }
}
