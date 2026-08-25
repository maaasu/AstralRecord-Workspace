package io.github.maaasu.astralRecord.feature.market.event;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketListingEligibilityTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_4-統合フロー.md
     * 章・見出し: # 23_4-統合フロー > ## 5. サーバー内 GUI の出品・購入
     * 検証契約: 取引可能なBUNDLE、MATERIAL、ORB、CONSUMABLE、SKILL_GEM、SIGILのstack itemは、固定カテゴリ列挙により出品候補から除外されない。
     */
    @Test
    void acceptsTradeableStackItemsAcrossSupportedNonCurrencyCategories() {
        for (ItemCategory category : List.of(
            ItemCategory.BUNDLE,
            ItemCategory.MATERIAL,
            ItemCategory.ORB,
            ItemCategory.CONSUMABLE,
            ItemCategory.SKILL_GEM,
            ItemCategory.SIGIL,
            ItemCategory.RUNE
        )) {
            assertTrue(MarketListingEligibility.isEligible(stackEntry(category, null), item(false)), category.name());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_4-統合フロー.md
     * 章・見出し: # 23_4-統合フロー > ## 5. サーバー内 GUI の出品・購入
     * 検証契約: stack itemのinstance typeが空白でも未指定として扱い、数量が正なら出品候補にできる。
     */
    @Test
    void acceptsStackEntryWithBlankInstanceType() {
        assertTrue(MarketListingEligibility.isEligible(stackEntry(ItemCategory.MATERIAL, "  "), item(false)));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_4-統合フロー.md
     * 章・見出し: # 23_4-統合フロー > ## 5. サーバー内 GUI の出品・購入
     * 検証契約: Gold、取引不可item、不明カテゴリ、カテゴリと一致しない個体typeは出品候補にできない。
     */
    @Test
    void rejectsCurrencyUntradeableAndMalformedEntries() {
        assertFalse(MarketListingEligibility.isEligible(stackEntry(ItemCategory.CURRENCY, null), item(false)));
        assertFalse(MarketListingEligibility.isEligible(stackEntry(ItemCategory.MATERIAL, null), item(true)));
        assertFalse(MarketListingEligibility.isEligible(stackEntry(ItemCategory.UNKNOWN, null), item(false)));
        assertFalse(MarketListingEligibility.isEligible(
            instanceEntry(ItemCategory.SIGIL, InventoryInstanceType.EQUIPMENT),
            item(false)
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_4-統合フロー.md
     * 章・見出し: # 23_4-統合フロー > ## 5. サーバー内 GUI の出品・購入
     * 検証契約: 売却不可フラグと売値 0 はマーケット対象外ではなく、API の単価ガードで売値以下だけを拒否する。
     */
    @Test
    void acceptsUnsellableAndZeroSellValueItemsForApiPriceGuard() {
        assertTrue(MarketListingEligibility.isEligible(
            stackEntry(ItemCategory.MATERIAL, null),
            item(false, true, 0)
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_4-統合フロー.md
     * 章・見出し: # 23_4-統合フロー > ## 5. サーバー内 GUI の出品・購入
     * 検証契約: EQUIPMENT個体は出品候補にできるが、廃止済みのRUNE個体entryは拒否する。
     */
    @Test
    void acceptsEquipmentInstanceAndRejectsLegacyRuneInstance() {
        assertTrue(MarketListingEligibility.isEligible(
            instanceEntry(ItemCategory.EQUIPMENT, InventoryInstanceType.EQUIPMENT),
            item(false)
        ));
        assertFalse(MarketListingEligibility.isEligible(
            entry(ItemCategory.RUNE, "RUNE", UUID.randomUUID(), 1L),
            item(false)
        ));
    }

    private InventoryEntryModel stackEntry(ItemCategory category, String instanceType) {
        return entry(category, instanceType, null, 8L);
    }

    private InventoryEntryModel instanceEntry(ItemCategory category, InventoryInstanceType instanceType) {
        return entry(category, instanceType.getCode(), UUID.randomUUID(), 1L);
    }

    private InventoryEntryModel entry(ItemCategory category, String instanceType, UUID instanceId, long quantity) {
        UUID accountId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.of(2026, 8, 14, 0, 0);
        return new InventoryEntryModel(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            category.getApiValue(),
            "test_item",
            instanceType,
            instanceId,
            quantity,
            null,
            now,
            now,
            accountId,
            accountId,
            false
        );
    }

    private ItemModel item(boolean unTradeable) {
        return item(unTradeable, false, 1);
    }

    private ItemModel item(boolean unTradeable, boolean unSellable, int saleValue) {
        return new ItemModel(
            1,
            "test_item",
            ItemCategory.MATERIAL.getApiValue(),
            "検証アイテム",
            "STONE",
            "COMMON",
            64,
            saleValue,
            null,
            null,
            List.<String>of(),
            unTradeable,
            unSellable,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }
}
