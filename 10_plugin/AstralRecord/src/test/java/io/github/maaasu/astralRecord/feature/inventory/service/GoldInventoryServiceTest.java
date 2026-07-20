package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.currency.model.GoldDenomination;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoldInventoryServiceTest {

    @Test
    void automaticallyBreaksHigherDenominationAndReturnsCanonicalChange() {
        Harness harness = harness(GoldDenomination.GOLD_BLOCK, 1L);

        assertEquals(1_000L, harness.service.getGoldAmount(harness.accountId));
        assertTrue(harness.service.consumeGold(harness.accountId, 120L));

        assertEquals(880L, harness.service.getGoldAmount(harness.accountId));
        assertEquals(8L, harness.service.getCurrencyAmount(
            harness.accountId,
            GoldDenomination.GOLD_INGOT.itemId()
        ));
        assertEquals(8L, harness.service.getCurrencyAmount(
            harness.accountId,
            GoldDenomination.GOLD_COIN.itemId()
        ));
        assertEquals(0L, harness.service.getCurrencyAmount(
            harness.accountId,
            GoldDenomination.GOLD_BLOCK.itemId()
        ));
    }

    @Test
    void exchangesOnlyTheSelectedAdjacentDenominationAtEqualValue() {
        Harness harness = harness(GoldDenomination.GOLD_INGOT, 10L);

        assertTrue(harness.service.exchangeCurrency(
            harness.accountId,
            GoldDenomination.GOLD_INGOT.itemId(),
            10L,
            GoldDenomination.GOLD_BLOCK.itemId(),
            1L
        ));

        assertEquals(0L, harness.service.getCurrencyAmount(
            harness.accountId,
            GoldDenomination.GOLD_INGOT.itemId()
        ));
        assertEquals(1L, harness.service.getCurrencyAmount(
            harness.accountId,
            GoldDenomination.GOLD_BLOCK.itemId()
        ));
        assertEquals(1_000L, harness.service.getGoldAmount(harness.accountId));
    }

    private static Harness harness(GoldDenomination denomination, long amount) {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryModel currency = DesignTestFixtures.inventory(accountId, InventoryType.CURRENCY, 27);
        state.putInventory(currency);
        state.replaceEntriesFromLoad(currency.getInventoryId(), List.of(currencyEntry(
            accountId,
            currency.getInventoryId(),
            denomination.itemId(),
            amount
        )));
        registry.put(state);

        ItemService itemService = mock(ItemService.class);
        when(itemService.loadItem(anyString())).thenAnswer(invocation -> DesignTestFixtures.item(
            invocation.getArgument(0, String.class),
            ItemCategory.CURRENCY,
            64
        ));
        InventoryService service = new InventoryService(
            mock(InventoryRepository.class),
            mock(EquipmentLoadoutRepository.class),
            itemService,
            mock(ItemStackFactory.class),
            registry,
            mock(InventoryPersistence.class),
            mock(InventorySaveCoordinator.class)
        );
        return new Harness(accountId, service);
    }

    private static InventoryEntryModel currencyEntry(
        UUID accountId,
        UUID inventoryId,
        String itemId,
        long quantity
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            UUID.randomUUID(),
            inventoryId,
            1,
            ItemCategory.CURRENCY.getApiValue(),
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

    private record Harness(UUID accountId, InventoryService service) {
    }
}
