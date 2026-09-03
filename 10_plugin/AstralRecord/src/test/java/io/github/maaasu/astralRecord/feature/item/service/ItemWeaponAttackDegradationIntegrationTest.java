package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.combat.service.NormalAttackDegradationService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItemWeaponAttackDegradationIntegrationTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 12. 通常攻撃劣化
     * 検証契約: StatusService注入済みの通常攻撃castは空振りでも連続回数へ加算され、遅延値3によって8回目で段階1へ到達する。
     */
    @Test
    void successfulNormalAttackCastAdvancesDegradationEvenWhenTheAttackHasNoTarget() {
        InventoryService inventoryService = mock(InventoryService.class);
        SkillService skillService = mock(SkillService.class);
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        player.selectClass("swordsman");
        StatusService statusService = mock(StatusService.class);
        when(statusService.getStatus(player)).thenReturn(StatusSnapshot.empty().withFlatBonuses(
                Map.of(StatusType.NORMAL_ATTACK_DEGRADATION_DELAY, 3.0D)
        ));
        NormalAttackDegradationService degradationService = new NormalAttackDegradationService(statusService);
        ItemModel weapon = weaponModel();
        when(inventoryService.getItemModelInHand(player, EquipmentSlot.HAND)).thenReturn(weapon);
        when(skillService.isOnCooldown(any(), eq(SkillService.WEAPON_NORMAL_ATTACK_COOLDOWN_ID))).thenReturn(false);
        when(skillService.castSkill(
                any(),
                eq(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MELEE),
                eq(SkillCastTrigger.AUTO_ATTACK),
                any(Location.class),
                isNull(),
                any()
        )).thenReturn(SkillCastResult.succeeded());

        ItemWeaponAttackService service = new ItemWeaponAttackService(
                inventoryService,
                skillService,
                degradationService
        );
        Location location = new Location(null, 0.0D, 0.0D, 0.0D);

        try {
            for (int index = 0; index < 8; index++) {
                service.handleLeftClick(player, location);
            }

            assertEquals(1, degradationService.currentStage(player));
        } finally {
            degradationService.stop();
        }
    }

    private ItemModel weaponModel() {
        ItemModel model = mock(ItemModel.class);
        ItemEquipment equipment = mock(ItemEquipment.class);
        when(model.getEquipment()).thenReturn(equipment);
        when(equipment.getSlot()).thenReturn(ItemEquipmentSlot.WEAPON);
        when(equipment.getTag()).thenReturn("SWORD");
        when(equipment.getRequiredLevel()).thenReturn(0);
        when(equipment.getRequiredClasses()).thenReturn(List.of());
        return model;
    }
}
