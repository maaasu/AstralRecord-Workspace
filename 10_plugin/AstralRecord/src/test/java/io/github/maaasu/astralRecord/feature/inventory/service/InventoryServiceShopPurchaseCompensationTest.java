package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryServiceShopPurchaseCompensationTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_5-例外・ログ・運用.md
     * 章・見出し: # 20_5-例外・ログ・運用 > ## 購入後スキル mutation
     * 検証契約: API拒否時は今回購入entryだけを除去し、Gold・通貨・通常素材を全返却して無関係entryを維持する。
     */
    @Test
    void compensatesOnlyPurchasedItemAndPreservesConcurrentInventoryChanges() {
        UUID accountId = UUID.randomUUID();
        UUID purchasedEntryId = UUID.randomUUID();
        UUID originalEntryId = UUID.randomUUID();
        String purchasedItemId = "purchased_reward";
        String originalItemId = "preexisting_reward";
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryModel bag = DesignTestFixtures.inventory(accountId, InventoryType.BAG, 27);
        InventoryModel currency = DesignTestFixtures.inventory(accountId, InventoryType.CURRENCY, 27);
        state.putInventory(bag);
        state.putInventory(currency);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(
            entry(purchasedEntryId, accountId, bag.getInventoryId(), 1, ItemCategory.MATERIAL, purchasedItemId, 1L),
            entry(originalEntryId, accountId, bag.getInventoryId(), 2, ItemCategory.MATERIAL, originalItemId, 1L),
            entry(UUID.randomUUID(), accountId, bag.getInventoryId(), 3, ItemCategory.MATERIAL, "concurrent_reward", 2L)
        ));
        state.replaceEntriesFromLoad(currency.getInventoryId(), List.of(
            entry(UUID.randomUUID(), accountId, currency.getInventoryId(), 1, ItemCategory.CURRENCY, "gold", 5L)
        ));
        registry.put(state);

        ItemService itemService = mock(ItemService.class);
        when(itemService.loadItem(anyString())).thenAnswer(invocation -> DesignTestFixtures.item(
            invocation.getArgument(0, String.class),
            ItemCategory.CURRENCY,
            64
        ));
        when(itemService.loadItem(anyString(), anyString())).thenAnswer(invocation -> {
            String itemId = invocation.getArgument(0, String.class);
            ItemCategory category = ItemCategory.fromApiValue(invocation.getArgument(1, String.class));
            return DesignTestFixtures.item(itemId, category, 64);
        });
        InventoryService service = new InventoryService(
            mock(InventoryRepository.class),
            mock(EquipmentLoadoutRepository.class),
            itemService,
            mock(ItemStackFactory.class),
            registry,
            mock(InventoryPersistence.class),
            mock(InventorySaveCoordinator.class)
        );

        assertTrue(service.compensateFailedShopPurchase(
            accountId,
            purchasedEntryId,
            purchasedItemId,
            7L,
            List.of(
                new InventoryService.InventoryRefundItem("skill_gem_raw", "material", 3),
                new InventoryService.InventoryRefundItem("astrald", "currency", 2)
            )
        ));

        assertNull(service.findOwnedEntry(accountId, purchasedEntryId));
        assertEquals(1L, service.getNormalItemAmount(accountId, originalItemId));
        assertEquals(2L, service.getNormalItemAmount(accountId, "concurrent_reward"));
        assertEquals(3L, service.getNormalItemAmount(accountId, "skill_gem_raw"));
        assertEquals(2L, service.getCurrencyAmount(accountId, "astrald"));
        assertEquals(12L, service.getGoldAmount(accountId));
    }

    private static InventoryEntryModel entry(
        UUID entryId,
        UUID accountId,
        UUID inventoryId,
        int slot,
        ItemCategory category,
        String itemId,
        long quantity
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            entryId,
            inventoryId,
            slot,
            category.getApiValue(),
            itemId,
            null,
            null,
            quantity,
            null,
            now,
            now,
            accountId,
            accountId,
            false
        );
    }
}
