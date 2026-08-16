package io.github.maaasu.astralRecord.feature.market.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketListingDraftTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_1-モデル定義.md
     * 章・見出し: # 23_1-モデル定義 > ## Quote・summary・transaction > ### MarketListingDraft
     * 検証契約: 同種 stack を複数 source から出品するとき、クリックした source を先頭に選択数量だけを順に割り当てる。
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
}
