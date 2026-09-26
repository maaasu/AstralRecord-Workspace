package io.github.maaasu.astralRecord.feature.market.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketListingDraftTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_1-モデル定義.md
     * 章・見出し: # 23_1-モデル定義 > ## Quote・summary・transaction > ### MarketListingDraft
     * 検証契約: 同種 stack を複数 source から出品するとき、渡された共通消費順の source へ選択数量だけを順に割り当てる。
     */
    @Test
    void selectedSourcesAllocateQuantityAcrossMatchingInventoryEntries() {
        UUID firstEntryId = UUID.randomUUID();
        UUID secondEntryId = UUID.randomUUID();
        MarketListingDraft draft = new MarketListingDraft(
            UUID.randomUUID(),
            List.of(
                new MarketListingSource(firstEntryId, 2L),
                new MarketListingSource(secondEntryId, 5L)
            ),
            "MATERIAL",
            "test_item",
            null,
            null,
            7L,
            2L
        );
        draft.setQuantity(5L);

        assertEquals(List.of(
            new MarketListingSource(firstEntryId, 2L),
            new MarketListingSource(secondEntryId, 3L)
        ), draft.selectedSources());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_1-モデル定義.md
     * 章・見出し: # 23_1-モデル定義 > ## Quote・summary・transaction > ### MarketListingDraft
     * 検証契約: 相場中央値を初期単価に優先し、売値超と数量積の安全上限を守りつつ参考上限をクイック最大に使う。
     */
    @Test
    void recommendedAndQuickMaximumRespectSaleFloorAndQuantityOverflow() {
        MarketListingDraft draft = new MarketListingDraft(
            UUID.randomUUID(), List.of(), "MATERIAL", "test_item", null, null, 10L, 321L);
        draft.setPriceQuote(new MarketPriceQuote(
            "MATERIAL", "test_item", null, null, 320L, 500L, 1_000L,
            11, "ITEM", "MEDIUM", 400L, 3_000L, "ALLOW", null, null, null, Instant.EPOCH));

        assertEquals(321L, draft.minimumUnitPrice());
        assertEquals(1_000L, draft.recommendedUnitPrice());
        assertEquals(3_000L, draft.quickMaxUnitPrice());

        draft.setQuantity(10L);
        assertEquals(Long.MAX_VALUE / 10L, draft.maximumUnitPrice());
        draft.setUnitPrice(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE / 10L, draft.unitPrice());
        draft.setPriceQuote(new MarketPriceQuote(
            "MATERIAL", "test_item", null, null, 320L, 250L, null,
            0, "ITEM", "LOW", 320L, Long.MAX_VALUE, "ALLOW", null, null, null, Instant.EPOCH));
        assertEquals(321L, draft.recommendedUnitPrice());
        assertEquals(Long.MAX_VALUE / 10L, draft.quickMaxUnitPrice());
    }
}
