package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceFailAction;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceLevel;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemEnhanceServiceTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 管理者強化値変更
     * 検証契約: 選択候補があっても個体の所有者が異なる場合は変更せず保存へ登録しない。
     */
    @Test
    void rejectsForeignEquipmentAtMutationBoundary() {
        Fixture fixture = fixture();
        when(fixture.items.findLoadedEquipmentInstanceById(fixture.instanceId.toString()))
            .thenReturn(instance(fixture.instanceId, UUID.randomUUID(), 2));
        assertNull(fixture.service.setLevel(fixture.player, "[1] Sword", "3"));
        verify(fixture.items, never()).applyLocalEquipmentInstance(any());
        verify(fixture.inventory, never()).queueLocalPlayerSave(any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 管理者強化値変更
     * 検証契約: 選択後に正本entryの個体が変わった場合は、元の個体と移動先の個体を変更しない。
     */
    @Test
    void rejectsChangedInventoryIdentity() {
        Fixture fixture = fixture();
        when(fixture.entry.getInstanceId()).thenReturn(UUID.randomUUID());
        assertNull(fixture.service.setLevel(fixture.player, "[1] Sword", "+1"));
        verify(fixture.items, never()).applyLocalEquipmentInstance(any());
        verify(fixture.inventory, never()).queueLocalPlayerSave(any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 管理者強化値変更
     * 検証契約: 所有者照合済みの減算変更は強化と耐久を一体反映し、非同期保存へ登録する。
     */
    @Test
    void commitsReducedLevelAndDurabilityThroughLocalSaveBoundary() {
        Fixture fixture = fixture();
        var result = fixture.service.setLevel(fixture.player, "[1] Sword", "-9999999999999999999999999");
        assertEquals(2, result.previousLevel());
        assertEquals(0, result.instance().getEnhanceLevel());
        assertEquals(100, result.instance().getDurabilityMax());
        assertEquals(80, result.instance().getDurabilityValue());
        verify(fixture.items).applyLocalEquipmentInstance(result.instance());
        verify(fixture.inventory).queueLocalPlayerSave(fixture.accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 管理者強化値変更
     * 検証契約: 補正後の強化値が現在値と同じならdirty登録と保存登録を増やさない。
     */
    @Test
    void keepsUnchangedEquipmentClean() {
        Fixture fixture = fixture();
        var result = fixture.service.setLevel(fixture.player, "[1] Sword", "+0");
        assertSame(fixture.current, result.instance());
        verify(fixture.items, never()).applyLocalEquipmentInstance(any());
        verify(fixture.inventory, never()).queueLocalPlayerSave(any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 管理者強化値変更
     * 検証契約: 外部所持品操作が未確定の場合は、強化値変更と保存登録の前に拒否する。
     */
    @Test
    void refusesMutationWhileExternalOperationIsPending() {
        Fixture fixture = fixture();
        when(fixture.inventory.executeLocalPlayerMutation(eq(fixture.accountId), any()))
            .thenThrow(new InventorySaveCoordinator.ExternalOperationPendingException(fixture.accountId));
        assertThrows(InventorySaveCoordinator.ExternalOperationPendingException.class,
            () -> fixture.service.setLevel(fixture.player, "[1] Sword", "+1"));
        verify(fixture.items, never()).applyLocalEquipmentInstance(any());
        verify(fixture.inventory, never()).queueLocalPlayerSave(any());
    }

    private static Fixture fixture() {
        UUID accountId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        UUID inventoryId = UUID.randomUUID();
        ItemService items = mock(ItemService.class);
        InventoryService inventory = mock(InventoryService.class);
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(player.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        InventoryEntryModel entry = mock(InventoryEntryModel.class);
        when(entry.getInventoryId()).thenReturn(inventoryId);
        when(entry.getSlotIndex()).thenReturn(1);
        when(entry.getQuantity()).thenReturn(1L);
        when(entry.getInstanceId()).thenReturn(instanceId);
        when(entry.getItemCategory()).thenReturn(ItemCategory.EQUIPMENT.getApiValue());
        when(entry.getInstanceType()).thenReturn(InventoryInstanceType.EQUIPMENT.getCode());
        when(inventory.findOwnedEntry(accountId, entryId)).thenReturn(entry);
        when(inventory.executeLocalPlayerMutation(eq(accountId), any()))
            .thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        EquipmentInstance current = instance(instanceId, accountId, 2);
        when(items.findLoadedEquipmentInstanceById(instanceId.toString())).thenReturn(current);
        when(items.applyLocalEquipmentInstance(any())).thenAnswer(call -> call.getArgument(0));
        ItemEquipment equipment = mock(ItemEquipment.class);
        when(equipment.getSlot()).thenReturn(ItemEquipmentSlot.WEAPON);
        when(equipment.getEnhance()).thenReturn(new ItemEquipmentEnhance(3, List.of(level(1), level(2), level(3))));
        when(equipment.getTranscendence()).thenReturn(List.of());
        ItemModel model = mock(ItemModel.class);
        when(model.getCategory()).thenReturn(ItemCategory.EQUIPMENT.getApiValue());
        when(model.getEquipment()).thenReturn(equipment);
        when(items.findLoadedById("equipment")).thenReturn(model);
        ItemEnhanceService service = spy(new ItemEnhanceService(items, inventory, mock(ItemChatShareService.class)));
        doReturn(List.of(new ItemEnhanceService.Candidate(entryId, inventoryId, 1, "Sword", instanceId.toString(), 3)))
            .when(service).getCandidates(player);
        return new Fixture(accountId, instanceId, items, inventory, player, entry, current, service);
    }

    private static ItemEquipmentEnhanceLevel level(int level) {
        return new ItemEquipmentEnhanceLevel(level, List.of(), 10, 1.0D, ItemEquipmentEnhanceFailAction.NONE, null);
    }

    private static EquipmentInstance instance(UUID id, UUID owner, int level) {
        return new EquipmentInstance(id.toString(), owner.toString(), "equipment", level, 0, 0,
            100 + level * 10, 80 + level * 10, "created", "updated", List.of(), List.of(), List.of());
    }

    private record Fixture(UUID accountId, UUID instanceId, ItemService items, InventoryService inventory,
        AstPlayer player, InventoryEntryModel entry, EquipmentInstance current, ItemEnhanceService service) {
    }
}
