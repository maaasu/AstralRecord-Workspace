package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InventoryItemStackResolverTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 3. ItemStack 変換
     * 検証契約: itemIdを併記したEQUIPMENT entryでもinstanceType / instanceIdを優先し、個体情報付きItemStackを復元する。
     */
    @Test
    void itemIdPopulatedEquipmentEntryUsesInstanceItemStack() {
        ItemService itemService = mock(ItemService.class);
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        InventoryItemStackResolver resolver = new InventoryItemStackResolver(itemService, itemStackFactory);
        UUID accountId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        ItemModel model = DesignTestFixtures.equipmentItem(
            "durable_sword",
            "ATTACK",
            io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType.FLAT
        );
        EquipmentInstance instance = DesignTestFixtures.equipmentInstance(
            instanceId,
            accountId,
            model.getId(),
            "ATTACK",
            "1",
            "2"
        );
        String metadataJson = "{\"hookshot\":{\"loaded\":true}}";
        ItemStack instanceStack = mock(ItemStack.class);
        when(itemService.findLoadedEquipmentInstanceById(instanceId.toString())).thenReturn(instance);
        when(itemService.findLoadedById(model.getId())).thenReturn(model);
        when(itemStackFactory.create(model, instance, 1, metadataJson)).thenReturn(instanceStack);

        InventoryEntryModel entry = new InventoryEntryModel(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            ItemCategory.EQUIPMENT.getApiValue(),
            model.getId(),
            "EQUIPMENT",
            instanceId,
            1L,
            metadataJson,
            LocalDateTime.now(),
            LocalDateTime.now(),
            accountId,
            accountId,
            false
        );

        assertSame(instanceStack, resolver.resolve(entry, accountId));
        verify(itemStackFactory).create(eq(model), eq(instance), eq(1), eq(metadataJson));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 3. ItemStack 変換
     * 検証契約: instanceType と instanceId が対でない、または未知の個体種別を持つ entry は通常 stack へフォールバックしない。
     */
    @Test
    void malformedInstanceMetadataDoesNotFallbackToNormalItem() {
        ItemService itemService = mock(ItemService.class);
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        InventoryItemStackResolver resolver = new InventoryItemStackResolver(itemService, itemStackFactory);
        UUID accountId = UUID.randomUUID();

        InventoryEntryModel missingInstanceId = entry("durable_sword", "EQUIPMENT", null, accountId);
        InventoryEntryModel unknownInstanceType = entry(
            "durable_sword",
            "UNKNOWN",
            UUID.randomUUID(),
            accountId
        );

        assertNull(resolver.resolve(missingInstanceId, accountId));
        assertNull(resolver.resolve(unknownInstanceType, accountId));
        verifyNoInteractions(itemStackFactory);
    }

    private static InventoryEntryModel entry(
        String itemId,
        String instanceType,
        UUID instanceId,
        UUID accountId
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            ItemCategory.EQUIPMENT.getApiValue(),
            itemId,
            instanceType,
            instanceId,
            1L,
            null,
            now,
            now,
            accountId,
            accountId,
            false
        );
    }
}
