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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoldInventoryServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/16-currency/16_3-メソッド仕様.md
     * 章・見出し: # 16_3-メソッド仕様 > ## ゴールド消費（inventory 委譲）
     * 検証契約: 不足時だけ上位額面を崩し、支払後残高をcanonical低額面構成へ再構築する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/16-currency/16_3-メソッド仕様.md
     * 章・見出し: # 16_3-メソッド仕様 > ## 等価交換（inventory 委譲）
     * 検証契約: 選択額面と隣接上位額面だけを等価交換し他額面を変更しない。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/16-currency/16_3-メソッド仕様.md
     * 章・見出し: # 16_3-メソッド仕様 > ## ゴールド消費（inventory 委譲）
     * 検証契約: 低額面を先に消費し不足分に限って上位額面を崩す。
     */
    @Test
    void consumesSmallDenominationsBeforeBreakingHigherOnes() {
        Harness harness = harness(Map.of(
            GoldDenomination.GOLD, 5L,
            GoldDenomination.GOLD_COIN, 9L,
            GoldDenomination.GOLD_INGOT, 8L
        ));

        assertTrue(harness.service.consumeGold(harness.accountId, 120L));

        assertEquals(775L, harness.service.getGoldAmount(harness.accountId));
        assertEquals(5L, harness.service.getCurrencyAmount(harness.accountId, GoldDenomination.GOLD.itemId()));
        assertEquals(7L, harness.service.getCurrencyAmount(harness.accountId, GoldDenomination.GOLD_COIN.itemId()));
        assertEquals(7L, harness.service.getCurrencyAmount(harness.accountId, GoldDenomination.GOLD_INGOT.itemId()));
    }

    private static Harness harness(GoldDenomination denomination, long amount) {
        return harness(Map.of(denomination, amount));
    }

    private static Harness harness(Map<GoldDenomination, Long> balances) {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryModel currency = DesignTestFixtures.inventory(accountId, InventoryType.CURRENCY, 27);
        state.putInventory(currency);
        state.replaceEntriesFromLoad(currency.getInventoryId(), balances.entrySet().stream()
            .map(entry -> currencyEntry(
                accountId,
                currency.getInventoryId(),
                entry.getKey().itemId(),
                entry.getValue()
            ))
            .toList());
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
