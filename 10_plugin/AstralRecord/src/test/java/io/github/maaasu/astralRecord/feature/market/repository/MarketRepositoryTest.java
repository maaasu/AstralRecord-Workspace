package io.github.maaasu.astralRecord.feature.market.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketRepositoryTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_4-統合フロー.md
     * 章・見出し: # 23_4-統合フロー > ## 4. 購入
     * 検証契約: 購入 receipt は1件以上の affected inventory entry IDを返す。
     */
    @Test
    void parseTransactionAcceptsRequiredAffectedInventoryEntryIds() {
        MarketRepository repository = new MarketRepository();
        JsonObject response = validTransactionResponse();
        UUID affectedEntryId = UUID.randomUUID();
        response.add("affectedInventoryEntryIds", JsonParser.parseString(
            "[\"" + affectedEntryId + "\"]"));

        var transaction = repository.parseTransaction(response);

        assertEquals(List.of(affectedEntryId), transaction.affectedInventoryEntryIds());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_4-統合フロー.md
     * 章・見出し: # 23_4-統合フロー > ## 4. 購入
     * 検証契約: affected inventory entry IDの欠落・空・不正値は購入成功として扱わない。
     */
    @Test
    void parseTransactionRejectsMissingEmptyAndInvalidAffectedInventoryEntryIds() {
        MarketRepository repository = new MarketRepository();
        JsonObject missing = validTransactionResponse();
        JsonObject empty = validTransactionResponse();
        empty.add("affectedInventoryEntryIds", new JsonArray());
        JsonObject invalid = validTransactionResponse();
        invalid.add("affectedInventoryEntryIds", JsonParser.parseString("[\"invalid\"]"));

        assertThrows(IllegalStateException.class, () -> repository.parseTransaction(missing));
        assertThrows(IllegalStateException.class, () -> repository.parseTransaction(empty));
        assertThrows(IllegalStateException.class, () -> repository.parseTransaction(invalid));
    }

    private static JsonObject validTransactionResponse() {
        JsonObject response = new JsonObject();
        response.addProperty("transactionId", UUID.randomUUID().toString());
        response.addProperty("listingId", UUID.randomUUID().toString());
        response.addProperty("sellerAccountId", UUID.randomUUID().toString());
        response.addProperty("buyerAccountId", UUID.randomUUID().toString());
        response.addProperty("itemCategory", "material");
        response.addProperty("itemId", "market_test_material");
        response.addProperty("quantity", 1L);
        response.addProperty("currencyId", "gold");
        response.addProperty("unitPrice", 1L);
        response.addProperty("totalPrice", 1L);
        response.addProperty("feeAmount", 0L);
        response.addProperty("sellerProceeds", 1L);
        response.addProperty("completedAt", Instant.parse("2026-08-30T00:00:00Z").toString());
        return response;
    }
}
