package io.github.maaasu.astralRecord.feature.item.castdisk;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentDurability;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.DodgeService;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingService;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CastDiskUseServiceDurabilityTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### キャストディスク
     * 検証契約: スキルキャストディスクの耐久は実際のスキル実行が成功した場合だけ消費する。
     */
    @Test
    @SuppressWarnings("unchecked")
    void consumesDurabilityOnlyAfterSkillExecutionSucceeds() {
        InventoryService inventoryService = mock(InventoryService.class);
        ItemService itemService = mock(ItemService.class);
        SkillActionRingService actionRingService = mock(SkillActionRingService.class);
        DodgeService dodgeService = mock(DodgeService.class);
        CastDiskGui gui = mock(CastDiskGui.class);
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        InventoryEntryModel diskEntry = mock(InventoryEntryModel.class);

        UUID accountId = UUID.randomUUID();
        Player bukkitPlayer = server().addPlayer();
        when(player.getBukkit()).thenReturn(bukkitPlayer);
        when(player.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(account.getLevel()).thenReturn(1);

        ItemReference diskReference = new ItemReference("20a00067", "equipment", "disk-instance");
        ItemModel diskModel = equipmentModel(
            "20a00067",
            "スキルキャストディスク",
            ItemEquipmentSlot.TOOL,
            MasterTagIds.Equipment.SKILL_CAST_DISK
        );
        ItemModel weaponModel = equipmentModel(
            "weapon-for-cast-disk",
            "テスト武器",
            ItemEquipmentSlot.WEAPON,
            MasterTagIds.Equipment.SWORD
        );
        EquipmentInstance diskInstance = equipmentInstance("disk-instance", diskModel, 200, 20);
        EquipmentInstance weaponInstance = equipmentInstance("weapon-instance", weaponModel, 100, 100);
        EquipmentInstance updatedDisk = equipmentInstance("disk-instance", diskModel, 200, 19);
        ItemStackFactory factory = new ItemStackFactory(mock(LootService.class), itemService);
        ItemStack weaponStack = factory.create(weaponModel, weaponInstance, 1);
        bukkitPlayer.getInventory().setItem(1, weaponStack);

        when(inventoryService.getHotbarEntryInHand(player, EquipmentSlot.HAND)).thenReturn(diskEntry);
        when(diskEntry.getMetadataJson()).thenReturn(
            "{\"castDisk\":{\"actionSlot\":0,\"weaponHotbarSlot\":1}}"
        );
        when(inventoryService.getItemReferenceInHand(player, EquipmentSlot.HAND)).thenReturn(diskReference);
        when(itemService.findLoadedById("20a00067")).thenReturn(diskModel);
        when(itemService.findLoadedById("weapon-for-cast-disk")).thenReturn(weaponModel);
        when(itemService.findEquipmentInstanceById("disk-instance")).thenReturn(diskInstance);
        when(itemService.findEquipmentInstanceById("weapon-instance")).thenReturn(weaponInstance);
        when(itemService.updateEquipmentDurability(
            eq("disk-instance"), eq(19), eq(accountId.toString())
        )).thenReturn(updatedDisk);
        when(actionRingService.castActionSlotWithHotbarWeapon(
            eq(player), eq(0), eq(1), any()
        )).thenReturn(true);

        CastDiskUseService service = new CastDiskUseService(
            (action, delayTicks) -> () -> { },
            ignored -> player,
            inventoryService,
            itemService,
            actionRingService,
            dodgeService,
            gui
        );

        service.handleLeftClick(player);

        ArgumentCaptor<Consumer<SkillCastResult>> completionCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(actionRingService).castActionSlotWithHotbarWeapon(
            eq(player), eq(0), eq(1), completionCaptor.capture()
        );
        verify(itemService, never()).updateEquipmentDurability(any(), any(Integer.class), any());

        completionCaptor.getValue().accept(SkillCastResult.failure(null));
        verify(itemService, never()).updateEquipmentDurability(any(), any(Integer.class), any());

        completionCaptor.getValue().accept(SkillCastResult.succeeded());
        verify(itemService).updateEquipmentDurability(
            "disk-instance", 19, accountId.toString()
        );
    }

    private ItemModel equipmentModel(String id, String name, ItemEquipmentSlot slot, String tag) {
        ItemEquipment equipment = new ItemEquipment(
            slot,
            ItemEquipmentHandType.ONE,
            tag,
            0,
            List.of(),
            null,
            List.of(),
            new ItemEquipmentDurability(slot == ItemEquipmentSlot.TOOL ? 200 : 100, 1),
            null,
            null,
            null,
            List.of()
        );
        return new ItemModel(
            1,
            id,
            ItemCategory.EQUIPMENT.getApiValue(),
            name,
            "PAPER",
            "UNCOMMON",
            1,
            0,
            null,
            null,
            List.of(),
            false,
            false,
            null,
            null,
            equipment,
            null,
            null,
            null,
            null
        );
    }

    private EquipmentInstance equipmentInstance(
        String instanceId,
        ItemModel model,
        int durabilityMax,
        int durabilityValue
    ) {
        return new EquipmentInstance(
            instanceId,
            "account-id",
            model.getId(),
            0,
            0,
            0,
            durabilityMax,
            durabilityValue,
            "",
            "",
            List.of(),
            List.of(),
            List.of()
        );
    }
}
