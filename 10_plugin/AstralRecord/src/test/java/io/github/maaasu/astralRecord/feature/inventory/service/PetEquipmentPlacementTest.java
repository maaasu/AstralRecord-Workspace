package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.*;
import io.github.maaasu.astralRecord.feature.inventory.repository.*;
import io.github.maaasu.astralRecord.feature.inventory.state.*;
import io.github.maaasu.astralRecord.feature.item.service.*;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PetEquipmentPlacementTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/37-pet/37_0-概要.md
     * 章・見出し: # 37_0-概要 > ## 装備と施設
     * 検証契約: BAG満杯で返却先のない解除は拒否し、装備とBAGの個体を変更しない。
     */
    @Test void refusesUnequipWhenBagHasNoReturnSlot() {
        Fixture fixture=new Fixture();
        fixture.fillBag();
        var before=fixture.state.snapshotInventoryMutationState();
        var result=fixture.service.preparePetEquipmentChange(fixture.owner,fixture.oldPet,null);
        assertNull(result.plan());assertTrue(result.bagFull());
        assertEquals(before,fixture.state.snapshotInventoryMutationState());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/37-pet/37_0-概要.md
     * 章・見出し: # 37_0-概要 > ## 装備と施設
     * 検証契約: 満杯のBAGから装備を交換するとき、新個体の元slotを旧個体の返却先として予約する。
     */
    @Test void reusesIncomingSlotForExchangeInFullBag() {
        Fixture fixture=new Fixture();fixture.fillBag();
        var before=fixture.state.snapshotEntries(fixture.bag.getInventoryId());
        var result=fixture.service.preparePetEquipmentChange(fixture.owner,fixture.oldPet,fixture.newPet);
        assertNotNull(result.plan());assertFalse(result.bagFull());
        assertEquals(1,result.plan().returnBagSlotIndex());
        assertNotNull(result.plan().reservation());
        assertEquals(before,fixture.state.snapshotEntries(fixture.bag.getInventoryId()));
        fixture.service.releasePetEquipmentPlan(result.plan());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/37-pet/37_0-概要.md
     * 章・見出し: # 37_0-概要 > ## 装備と施設
     * 検証契約: 同じ装備選択の確定は返却個体や返却予約を作らず、既存の装備個体を維持する。
     */
    @Test void unchangedSelectionNeedsNoReturnEntryOrSlot() {
        Fixture fixture=new Fixture();
        var result=fixture.service.preparePetEquipmentChange(fixture.owner,fixture.oldPet,fixture.oldPet);
        assertNotNull(result.plan());
        assertNull(result.plan().incomingEntryId());assertNull(result.plan().returningEntryId());
        assertNull(result.plan().reservation());assertNull(result.plan().returnBagSlotIndex());
        fixture.service.reconcileReservedPetEquipment(fixture.account,fixture.baseline(),
            new InventoryOperationSnapshot(fixture.account,Set.of(),List.of(),null,List.of()),result.plan());
        assertTrue(fixture.service.isPetInEquipmentSlot(fixture.account,fixture.oldPet));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/37-pet/37_0-概要.md
     * 章・見出し: # 37_0-概要 > ## 装備と施設
     * 検証契約: 装備交換の同一entry移動を一度だけ反映し、通信中の無関係なBAG報酬加算を保持する。
     */
    @Test void exchangeKeepsBothInstancesAndConcurrentMaterialGain() {
        Fixture fixture=new Fixture();fixture.fillBag();
        var baseline=fixture.baseline();
        var plan=fixture.service.preparePetEquipmentChange(fixture.owner,fixture.oldPet,fixture.newPet).plan();
        assertNotNull(plan);
        fixture.state.replaceEntries(fixture.bag.getInventoryId(),List.of(fixture.incoming,fixture.entry(
            fixture.material.getInventoryEntryId(),fixture.bag.getInventoryId(),2,null,8)));
        var movedIn=fixture.entry(fixture.incoming.getInventoryEntryId(),fixture.equip.getInventoryId(),7,fixture.newPet,1);
        var movedOut=fixture.entry(fixture.outgoing.getInventoryEntryId(),fixture.bag.getInventoryId(),1,fixture.oldPet,1);
        fixture.service.reconcileReservedPetEquipment(fixture.account,baseline,new InventoryOperationSnapshot(fixture.account,
            Set.of(movedIn.getInventoryEntryId(),movedOut.getInventoryEntryId()),List.of(movedIn,movedOut),null,List.of()),plan);
        var bag=fixture.state.snapshotEntries(fixture.bag.getInventoryId());
        var equip=fixture.state.snapshotEntries(fixture.equip.getInventoryId());
        assertEquals(2,bag.size());assertEquals(1,equip.size());
        assertEquals(fixture.newPet,equip.getFirst().getInstanceId());
        assertTrue(bag.stream().anyMatch(entry->fixture.oldPet.equals(entry.getInstanceId())&&entry.getSlotIndex()==1));
        assertEquals(8,bag.stream().filter(entry->entry.getInstanceId()==null).findFirst().orElseThrow().getQuantity());
        assertEquals(Set.of(fixture.incoming.getInventoryEntryId(),fixture.outgoing.getInventoryEntryId(),fixture.material.getInventoryEntryId()),
            java.util.stream.Stream.concat(bag.stream(),equip.stream()).map(InventoryEntryModel::getInventoryEntryId).collect(java.util.stream.Collectors.toSet()));
    }

    private static final class Fixture {
        final UUID account=UUID.randomUUID(),oldPet=UUID.randomUUID(),newPet=UUID.randomUUID();
        final PlayerInventoryState state=new PlayerInventoryState(account);
        final InventoryModel bag=DesignTestFixtures.inventory(account,InventoryType.BAG,null);
        final InventoryModel equip=DesignTestFixtures.inventory(account,InventoryType.EQUIP_SLOT,7);
        final InventoryEntryModel incoming=entry(UUID.randomUUID(),bag.getInventoryId(),1,newPet,1);
        final InventoryEntryModel outgoing=entry(UUID.randomUUID(),equip.getInventoryId(),7,oldPet,1);
        final InventoryEntryModel material=entry(UUID.randomUUID(),bag.getInventoryId(),2,null,5);
        final AstPlayer owner=mock(AstPlayer.class);
        final InventoryService service;
        Fixture(){
            state.setBagSlotCapacity(2);state.putInventory(bag);state.putInventory(equip);
            state.replaceEntriesFromLoad(equip.getInventoryId(),List.of(outgoing));
            PlayerInventoryStateRegistry registry=new PlayerInventoryStateRegistry();registry.put(state);
            AccountModel model=mock(AccountModel.class);when(model.getUuid()).thenReturn(account);when(owner.getAccount()).thenReturn(model);
            service=new InventoryService(mock(InventoryRepository.class),mock(EquipmentLoadoutRepository.class),mock(ItemService.class),
                mock(ItemStackFactory.class),registry,mock(InventoryPersistence.class),mock(InventorySaveCoordinator.class));
        }
        void fillBag(){state.replaceEntriesFromLoad(bag.getInventoryId(),List.of(incoming,material));}
        InventoryPersistence.PersistedInventoryBaseline baseline(){return new InventoryPersistence.PersistedInventoryBaseline(account,
            Map.of(bag.getInventoryId(),state.snapshotEntries(bag.getInventoryId()),equip.getInventoryId(),state.snapshotEntries(equip.getInventoryId())));}
        InventoryEntryModel entry(UUID id,UUID inventoryId,int slot,UUID pet,long amount){
            var time=LocalDateTime.of(2026,1,1,0,0);
            return new InventoryEntryModel(id,inventoryId,slot,pet==null?"material":"pet",pet==null?"material":"pet_item",
                pet==null?null:"PET",pet,amount,null,time,time,account,account,false);
        }
    }
}
