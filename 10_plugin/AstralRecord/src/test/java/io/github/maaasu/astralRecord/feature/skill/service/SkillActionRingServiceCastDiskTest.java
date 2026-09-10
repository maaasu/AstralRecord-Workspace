package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemWeaponAttackService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillActionRingServiceCastDiskTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 4. skill 発動 > ### 4.1 スキルキャストディスク
     * 検証契約: 指定武器への切替後に再計算したステータススナップショットが、スキル発動へ渡される。
     */
    @Test
    void castActionSlotUsesStatusSnapshotRecalculatedForConfiguredWeapon() {
        InventoryService inventoryService = mock(InventoryService.class);
        SkillBindPresetService presetService = mock(SkillBindPresetService.class);
        SkillService skillService = mock(SkillService.class);
        SkillOwnershipService ownershipService = mock(SkillOwnershipService.class);
        SkillPermissionService permissionService = mock(SkillPermissionService.class);
        StatusService statusService = mock(StatusService.class);
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        Player bukkitPlayer = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemModel weapon = mock(ItemModel.class);
        ItemEquipment equipment = mock(ItemEquipment.class);

        UUID accountId = UUID.randomUUID();
        Location eye = new Location(server().addSimpleWorld("cast_disk_status_world"), 0.0D, 64.0D, 0.0D);
        StatusSnapshot diskSnapshot = snapshot(100.0D, 80.0D);
        StatusSnapshot weaponSnapshot = snapshot(50.0D, 50.0D).withFlatBonuses(
            Map.of(StatusType.MELEE_ATTACK, 12.0D)
        );
        StatusSnapshot restoredSnapshot = snapshot(100.0D, 50.0D);
        AtomicReference<StatusSnapshot> currentSnapshot = new AtomicReference<>(diskSnapshot);
        AtomicInteger refreshCount = new AtomicInteger();

        when(player.getAccount()).thenReturn(account);
        when(player.getBukkit()).thenReturn(bukkitPlayer);
        when(player.getStatusSnapshot()).thenAnswer(invocation -> currentSnapshot.get());
        doAnswer(invocation -> {
            currentSnapshot.set(invocation.getArgument(0));
            return null;
        }).when(player).setStatusSnapshot(any(StatusSnapshot.class));
        when(account.getUuid()).thenReturn(accountId);
        when(account.getLevel()).thenReturn(1);
        when(bukkitPlayer.getInventory()).thenReturn(inventory);
        when(bukkitPlayer.getEyeLocation()).thenReturn(eye);
        when(inventory.getHeldItemSlot()).thenReturn(0);
        when(inventoryService.getItemModelInHand(player, EquipmentSlot.HAND)).thenReturn(weapon);
        when(weapon.getEquipment()).thenReturn(equipment);
        when(equipment.getSlot()).thenReturn(ItemEquipmentSlot.WEAPON);
        when(equipment.getRequiredLevel()).thenReturn(0);
        when(equipment.getRequiredClasses()).thenReturn(List.of());
        when(presetService.selectedPresetIndex(accountId)).thenReturn(0);
        when(presetService.getPresets(accountId)).thenReturn(List.of(new SkillBindPreset(
            null,
            accountId,
            0,
            List.of("skill-from-cast-disk"),
            null,
            List.of(),
            true,
            true,
            1
        )));
        doAnswer(invocation -> {
            StatusSnapshot next = refreshCount.getAndIncrement() == 0 ? weaponSnapshot : restoredSnapshot;
            currentSnapshot.set(next);
            return next;
        }).when(statusService).refreshStatus(player);
        when(skillService.castLearnedSkill(
            any(PlayerSkillCaster.class),
            eq("skill-from-cast-disk"),
            eq(SkillCastTrigger.PLAYER_COMMAND),
            eq(eye),
            isNull(),
            eq(List.of())
        )).thenAnswer(invocation -> {
            PlayerSkillCaster caster = invocation.getArgument(0);
            assertSame(weaponSnapshot, caster.statusSnapshot());
            return SkillCastResult.succeeded();
        });

        ItemWeaponAttackService weaponAttackService = new ItemWeaponAttackService(inventoryService, skillService);
        SkillActionRingService service = new SkillActionRingService(
            mock(AstralRecord.class),
            presetService,
            skillService,
            ownershipService,
            permissionService
        );
        service.setItemWeaponAttackService(weaponAttackService);
        service.setStatusService(statusService);

        assertTrue(service.castActionSlotWithHotbarWeapon(player, 0, 2));

        assertEquals(100.0D, currentSnapshot.get().getMaxValue(StatusType.MAX_MANA));
        assertEquals(80.0D, currentSnapshot.get().getCurrentMp());
        assertEquals(80.0D, currentSnapshot.get().getCurrentEnergy());
        verify(statusService, times(2)).refreshStatus(player);
        verify(inventory).setHeldItemSlot(2);
        verify(inventory).setHeldItemSlot(0);
    }

    private StatusSnapshot snapshot(double maxResource, double currentResource) {
        Map<StatusType, StatusValue> values = Map.of(
            StatusType.MAX_HEALTH, new StatusValue(maxResource, 0.0D),
            StatusType.MAX_MANA, new StatusValue(maxResource, 0.0D),
            StatusType.MAX_ENERGY, new StatusValue(maxResource, 0.0D)
        );
        return new StatusSnapshot(
            values,
            currentResource,
            currentResource,
            currentResource,
            0.0D,
            0L,
            LocalDateTime.now()
        );
    }
}
