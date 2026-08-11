package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.currency.model.GoldDenomination;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrbInventoryThreeWayMergerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: APIがbaseline stackを全消費しても、待機中に同stackへ加算した差分は削除済みIDを復活させず新entryとして保持し、無関係な新entryも保持する。
     */
    @Test
    void tombstoneKeepsPositiveLocalDeltaUnderNewEntryIdAndRetainsNewEntry() {
        UUID accountId = UUID.randomUUID();
        InventoryModel bag = DesignTestFixtures.inventory(accountId, InventoryType.BAG, 27);
        UUID consumedEntryId = UUID.randomUUID();
        UUID newEntryId = UUID.randomUUID();
        InventoryEntryModel baselineOrb = entry(
            consumedEntryId, bag.getInventoryId(), accountId, 1, ItemCategory.ORB, "orb.weapon_tyr", 1L);
        InventoryEntryModel locallyRewardedOrb = entry(
            consumedEntryId, bag.getInventoryId(), accountId, 1, ItemCategory.ORB, "orb.weapon_tyr", 2L);
        InventoryEntryModel unrelatedNew = entry(
            newEntryId, bag.getInventoryId(), accountId, 2, ItemCategory.MATERIAL, "reward.drop", 4L);

        var result = OrbInventoryThreeWayMerger.merge(
            accountId,
            List.of(bag),
            Map.of(bag.getInventoryId(), List.of(baselineOrb)),
            Map.of(bag.getInventoryId(), List.of(locallyRewardedOrb, unrelatedNew)),
            Map.of(consumedEntryId, Optional.empty()),
            null,
            List.of()
        );

        List<InventoryEntryModel> merged = result.entriesByInventoryId().get(bag.getInventoryId());
        assertFalse(merged.stream().anyMatch(entry -> entry.getInventoryEntryId().equals(consumedEntryId)));
        InventoryEntryModel retainedOrb = merged.stream()
            .filter(entry -> "orb.weapon_tyr".equals(entry.getItemId()))
            .findFirst()
            .orElseThrow();
        assertNotEquals(consumedEntryId, retainedOrb.getInventoryEntryId());
        assertEquals(1L, retainedOrb.getQuantity());
        assertTrue(merged.stream().anyMatch(entry ->
            entry.getInventoryEntryId().equals(newEntryId) && entry.getQuantity() == 4L));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: transition素材はAPI消費後数量へ待機中のローカル消費差分を一度だけ適用し、待機中のinventory移動とslotを維持する。
     */
    @Test
    void materialMergeAppliesConcurrentConsumeOnceAndKeepsLocalMove() {
        UUID accountId = UUID.randomUUID();
        InventoryModel bag = DesignTestFixtures.inventory(accountId, InventoryType.BAG, 27);
        InventoryModel hotbar = DesignTestFixtures.inventory(accountId, InventoryType.HOTBAR, 10);
        UUID entryId = UUID.randomUUID();
        InventoryEntryModel baseline = entry(
            entryId, bag.getInventoryId(), accountId, 1, ItemCategory.MATERIAL, "material.rune", 5L);
        InventoryEntryModel current = entry(
            entryId, hotbar.getInventoryId(), accountId, 5, ItemCategory.MATERIAL, "material.rune", 4L);
        InventoryEntryModel authoritative = entry(
            entryId, bag.getInventoryId(), accountId, 1, ItemCategory.MATERIAL, "material.rune", 2L);

        var result = OrbInventoryThreeWayMerger.merge(
            accountId,
            List.of(bag, hotbar),
            Map.of(bag.getInventoryId(), List.of(baseline), hotbar.getInventoryId(), List.of()),
            Map.of(bag.getInventoryId(), List.of(), hotbar.getInventoryId(), List.of(current)),
            Map.of(entryId, Optional.of(authoritative)),
            null,
            List.of()
        );

        assertTrue(result.entriesByInventoryId().get(bag.getInventoryId()).isEmpty());
        InventoryEntryModel merged = result.entriesByInventoryId().get(hotbar.getInventoryId()).getFirst();
        assertEquals(entryId, merged.getInventoryEntryId());
        assertEquals(1L, merged.getQuantity());
        assertEquals(5, merged.getSlotIndex());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: goldは額面entry単位で上書きせず、全正規額面とlegacyの総価値A+(C-B)を計算してcanonical額面へ再構成する。
     */
    @Test
    void goldMergePreservesConcurrentValueDeltaAndReconstructsCanonicalDenominations() {
        UUID accountId = UUID.randomUUID();
        InventoryModel currency = DesignTestFixtures.inventory(accountId, InventoryType.CURRENCY, null);
        UUID baselineGoldId = UUID.randomUUID();
        UUID localNewId = UUID.randomUUID();
        UUID authoritativeId = UUID.randomUUID();
        InventoryEntryModel baseline = entry(
            baselineGoldId, currency.getInventoryId(), accountId, 1,
            ItemCategory.CURRENCY, GoldDenomination.GOLD_INGOT.itemId(), 1L); // 100
        InventoryEntryModel currentBase = entry(
            baselineGoldId, currency.getInventoryId(), accountId, 1,
            ItemCategory.CURRENCY, GoldDenomination.GOLD_INGOT.itemId(), 1L);
        InventoryEntryModel localDelta = entry(
            localNewId, currency.getInventoryId(), accountId, 2,
            ItemCategory.CURRENCY, GoldDenomination.GOLD_COIN.itemId(), 2L); // net +20
        InventoryEntryModel authoritative = entry(
            authoritativeId, currency.getInventoryId(), accountId, 1,
            ItemCategory.CURRENCY, GoldDenomination.GOLD_COIN.itemId(), 6L); // API payment leaves 60

        var result = OrbInventoryThreeWayMerger.merge(
            accountId,
            List.of(currency),
            Map.of(currency.getInventoryId(), List.of(baseline)),
            Map.of(currency.getInventoryId(), List.of(currentBase, localDelta)),
            Map.of(baselineGoldId, Optional.empty(), authoritativeId, Optional.of(authoritative)),
            currency.getInventoryId(),
            List.of(authoritative)
        );

        List<InventoryEntryModel> merged = result.entriesByInventoryId().get(currency.getInventoryId());
        assertEquals(1, merged.size());
        InventoryEntryModel gold = merged.getFirst();
        assertEquals(authoritativeId, gold.getInventoryEntryId());
        assertEquals(GoldDenomination.GOLD_COIN.itemId(), gold.getItemId());
        assertEquals(8L, gold.getQuantity());
        assertEquals(80L, totalGoldValue(merged));
    }

    private static long totalGoldValue(List<InventoryEntryModel> entries) {
        long total = 0L;
        for (InventoryEntryModel entry : entries) {
            GoldDenomination denomination = GoldDenomination.findByItemId(entry.getItemId());
            if (denomination != null) {
                total += denomination.goldValue() * entry.getQuantity();
            }
        }
        return total;
    }

    private static InventoryEntryModel entry(
        UUID entryId,
        UUID inventoryId,
        UUID accountId,
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
