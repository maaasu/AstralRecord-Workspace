package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrb;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffect;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 3. ItemStack 変換
     * 検証契約: 通常BAG表示時だけ、ホットバー割当対象アイテムへクリック案内を追加し、
     * 通常の解決経路（HOTBAR・ストレージ等）へは追加しない。
     */
    @Test
    void bagDisplayAddsHotbarAssignmentLoreOnlyForAssignableItems() {
        ItemService itemService = mock(ItemService.class);
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        InventoryItemStackResolver resolver = new InventoryItemStackResolver(itemService, itemStackFactory);
        UUID accountId = UUID.randomUUID();
        ItemModel model = clickableModel(ItemCategory.BUNDLE);
        TestItemStack regularStack = mockedItemStack();
        TestItemStack bagStack = mockedItemStack();
        when(itemService.findLoadedById(model.getId())).thenReturn(model);
        when(itemStackFactory.create(model, 1)).thenReturn(regularStack.itemStack(), bagStack.itemStack());

        InventoryEntryModel entry = normalEntry(
            model.getId(),
            ItemCategory.BUNDLE.getApiValue(),
            accountId
        );

        resolver.resolve(entry, accountId);
        verifyNoInteractions(regularStack.itemStack());
        resolver.resolveForBag(entry, accountId);
        assertTrue(capturedLore(bagStack.itemMeta())
            .contains("クリックでホットバースロットに設定"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-イベント.md
     * 章・見出し: # 08_3-イベント > ## 1. インベントリクリック受付
     * 検証契約: 通常プレイヤーインベントリ内の有効なオーブ表示にはクリック使用案内を追加する。
     */
    @Test
    void bagDisplayAddsUseLoreForOrb() {
        ItemService itemService = mock(ItemService.class);
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        InventoryItemStackResolver resolver = new InventoryItemStackResolver(itemService, itemStackFactory);
        UUID accountId = UUID.randomUUID();
        ItemModel model = clickableModel(ItemCategory.ORB);
        ItemOrb orb = mock(ItemOrb.class);
        when(orb.getEffect()).thenReturn(mock(ItemOrbEffect.class));
        when(model.getOrb()).thenReturn(orb);
        TestItemStack stack = mockedItemStack();
        when(itemService.findLoadedById(model.getId())).thenReturn(model);
        when(itemStackFactory.create(model, 1)).thenReturn(stack.itemStack());

        InventoryEntryModel entry = normalEntry(
            model.getId(),
            ItemCategory.ORB.getApiValue(),
            accountId
        );

        resolver.resolveForBag(entry, accountId);
        assertTrue(capturedLore(stack.itemMeta()).contains("クリックで使用"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-イベント.md
     * 章・見出し: # 08_3-イベント > ## 1. インベントリクリック受付
     * 検証契約: 数量 0 または削除済みのオーブには、クリック使用案内を表示しない。
     */
    @Test
    void bagDisplayDoesNotAddUseLoreForInvalidOrbEntry() {
        ItemService itemService = mock(ItemService.class);
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        InventoryItemStackResolver resolver = new InventoryItemStackResolver(itemService, itemStackFactory);
        UUID accountId = UUID.randomUUID();
        ItemModel model = clickableModel(ItemCategory.ORB);
        ItemOrb orb = mock(ItemOrb.class);
        when(orb.getEffect()).thenReturn(mock(ItemOrbEffect.class));
        when(model.getOrb()).thenReturn(orb);
        TestItemStack stack = mockedItemStack();
        when(itemService.findLoadedById(model.getId())).thenReturn(model);
        when(itemStackFactory.create(model, 1)).thenReturn(stack.itemStack(), stack.itemStack());

        InventoryEntryModel zeroQuantity = normalEntry(
            model.getId(),
            ItemCategory.ORB.getApiValue(),
            accountId,
            0L,
            false
        );
        InventoryEntryModel deleted = normalEntry(
            model.getId(),
            ItemCategory.ORB.getApiValue(),
            accountId,
            1L,
            true
        );

        resolver.resolveForBag(zeroQuantity, accountId);
        resolver.resolveForBag(deleted, accountId);
        verifyNoInteractions(stack.itemStack());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 1. 統合所持品表示・スクロール
     * 検証契約: entry のカテゴリに従うクリック処理と表示案内の分類を一致させる。
     */
    @Test
    void bagDisplayUsesEntryCategoryForClickHint() {
        ItemService itemService = mock(ItemService.class);
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        InventoryItemStackResolver resolver = new InventoryItemStackResolver(itemService, itemStackFactory);
        UUID accountId = UUID.randomUUID();
        ItemModel model = clickableModel(ItemCategory.ORB);
        ItemOrb orb = mock(ItemOrb.class);
        when(orb.getEffect()).thenReturn(mock(ItemOrbEffect.class));
        when(model.getOrb()).thenReturn(orb);
        TestItemStack stack = mockedItemStack();
        when(itemService.findLoadedById(model.getId())).thenReturn(model);
        when(itemStackFactory.create(model, 1)).thenReturn(stack.itemStack());

        InventoryEntryModel entry = normalEntry(
            model.getId(),
            ItemCategory.CONSUMABLE.getApiValue(),
            accountId
        );

        resolver.resolveForBag(entry, accountId);
        List<String> lore = capturedLore(stack.itemMeta());
        assertTrue(lore.contains("クリックでホットバースロットに設定"));
        assertFalse(lore.contains("クリックで使用"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 4. ホットバー割当とショートカット表示
     * 検証契約: 武器・道具のうち、クリック処理が HOTBAR 割当へ進む装備には割当案内を追加する。
     */
    @Test
    void bagDisplayAddsHotbarLoreForWeaponEquipment() {
        ItemService itemService = mock(ItemService.class);
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        InventoryItemStackResolver resolver = new InventoryItemStackResolver(itemService, itemStackFactory);
        UUID accountId = UUID.randomUUID();
        ItemModel model = clickableModel(ItemCategory.EQUIPMENT);
        ItemEquipment equipment = mock(ItemEquipment.class);
        when(equipment.getSlot()).thenReturn(ItemEquipmentSlot.WEAPON);
        when(model.getEquipment()).thenReturn(equipment);
        TestItemStack stack = mockedItemStack();
        when(itemService.findLoadedById(model.getId())).thenReturn(model);
        when(itemStackFactory.create(model, 1)).thenReturn(stack.itemStack());

        InventoryEntryModel entry = normalEntry(
            model.getId(),
            ItemCategory.EQUIPMENT.getApiValue(),
            accountId
        );

        resolver.resolveForBag(entry, accountId);
        assertTrue(capturedLore(stack.itemMeta())
            .contains("クリックでホットバースロットに設定"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-イベント.md
     * 章・見出し: # 08_3-イベント > ## 1. インベントリクリック受付
     * 検証契約: TOOL 装備は通常 BAG クリックで HOTBAR 割当へ進むため、割当案内を追加する。
     */
    @Test
    void bagDisplayAddsHotbarLoreForToolEquipment() {
        ItemService itemService = mock(ItemService.class);
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        InventoryItemStackResolver resolver = new InventoryItemStackResolver(itemService, itemStackFactory);
        UUID accountId = UUID.randomUUID();
        ItemModel model = clickableModel(ItemCategory.EQUIPMENT);
        ItemEquipment equipment = mock(ItemEquipment.class);
        when(equipment.getSlot()).thenReturn(ItemEquipmentSlot.TOOL);
        when(model.getEquipment()).thenReturn(equipment);
        TestItemStack stack = mockedItemStack();
        when(itemService.findLoadedById(model.getId())).thenReturn(model);
        when(itemStackFactory.create(model, 1)).thenReturn(stack.itemStack());

        InventoryEntryModel entry = normalEntry(
            model.getId(),
            ItemCategory.EQUIPMENT.getApiValue(),
            accountId
        );

        resolver.resolveForBag(entry, accountId);
        assertTrue(capturedLore(stack.itemMeta())
            .contains("クリックでホットバースロットに設定"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-イベント.md
     * 章・見出し: # 08_3-イベント > ## 1. インベントリクリック受付
     * 検証契約: CONSUMABLE は通常 BAG クリックで HOTBAR 割当へ進むため、割当案内を追加する。
     */
    @Test
    void bagDisplayAddsHotbarLoreForConsumable() {
        ItemService itemService = mock(ItemService.class);
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        InventoryItemStackResolver resolver = new InventoryItemStackResolver(itemService, itemStackFactory);
        UUID accountId = UUID.randomUUID();
        ItemModel model = clickableModel(ItemCategory.CONSUMABLE);
        TestItemStack stack = mockedItemStack();
        when(itemService.findLoadedById(model.getId())).thenReturn(model);
        when(itemStackFactory.create(model, 1)).thenReturn(stack.itemStack());

        InventoryEntryModel entry = normalEntry(
            model.getId(),
            ItemCategory.CONSUMABLE.getApiValue(),
            accountId
        );

        resolver.resolveForBag(entry, accountId);
        assertTrue(capturedLore(stack.itemMeta())
            .contains("クリックでホットバースロットに設定"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-イベント.md
     * 章・見出し: # 08_3-イベント > ## 1. インベントリクリック受付
     * 検証契約: ホットバーへ割り当てない装備には、ホットバー割当案内を表示しない。
     */
    @Test
    void bagDisplayDoesNotAddHotbarLoreForNonAssignableEquipment() {
        ItemService itemService = mock(ItemService.class);
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        InventoryItemStackResolver resolver = new InventoryItemStackResolver(itemService, itemStackFactory);
        UUID accountId = UUID.randomUUID();
        ItemModel model = clickableModel(ItemCategory.EQUIPMENT);
        ItemEquipment equipment = mock(ItemEquipment.class);
        when(equipment.getSlot()).thenReturn(ItemEquipmentSlot.HEAD);
        when(model.getEquipment()).thenReturn(equipment);
        TestItemStack stack = mockedItemStack();
        when(itemService.findLoadedById(model.getId())).thenReturn(model);
        when(itemStackFactory.create(model, 1)).thenReturn(stack.itemStack());

        InventoryEntryModel entry = normalEntry(
            model.getId(),
            ItemCategory.EQUIPMENT.getApiValue(),
            accountId
        );

        resolver.resolveForBag(entry, accountId);
        verifyNoInteractions(stack.itemStack());
    }

    private static ItemModel clickableModel(ItemCategory category) {
        ItemModel model = mock(ItemModel.class);
        when(model.getId()).thenReturn("inventory-action-test-" + category.name().toLowerCase());
        when(model.getCategory()).thenReturn(category.getApiValue());
        when(model.getMaxStack()).thenReturn(64);
        return model;
    }

    @SuppressWarnings("unchecked")
    private static List<String> capturedLore(ItemMeta itemMeta) {
        ArgumentCaptor<List<? extends Component>> loreCaptor =
            (ArgumentCaptor<List<? extends Component>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(itemMeta).lore(loreCaptor.capture());
        List<? extends Component> lore = loreCaptor.getValue();
        return lore.stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .toList();
    }

    private static TestItemStack mockedItemStack() {
        ItemStack itemStack = mock(ItemStack.class);
        ItemMeta itemMeta = mock(ItemMeta.class);
        when(itemStack.getItemMeta()).thenReturn(itemMeta);
        when(itemMeta.lore()).thenReturn(List.of());
        return new TestItemStack(itemStack, itemMeta);
    }

    private static InventoryEntryModel normalEntry(
        String itemId,
        String itemCategory,
        UUID accountId
    ) {
        return normalEntry(itemId, itemCategory, accountId, 1L, false);
    }

    private static InventoryEntryModel normalEntry(
        String itemId,
        String itemCategory,
        UUID accountId,
        long quantity,
        boolean deleted
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            itemCategory,
            itemId,
            null,
            null,
            quantity,
            null,
            now,
            now,
            accountId,
            accountId,
            deleted
        );
    }

    private record TestItemStack(ItemStack itemStack, ItemMeta itemMeta) {
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
