package io.github.maaasu.astralRecord.feature.market.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.market.model.MarketCancelRequest;
import io.github.maaasu.astralRecord.feature.market.model.MarketListingCreateRequest;
import io.github.maaasu.astralRecord.feature.market.model.MarketListingSource;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_5-例外・ログ・運用.md
     * 章・見出し: # 23_5-例外・ログ・運用 > ## 例外方針
     * 検証契約: 取消の5xxは結果不明、確定4xxは拒否として区別する。
     */
    @Test
    void cancelClassifiesOutcomeUnknownAndDeterministicHttpStatuses() throws Exception {
        UUID listingId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        MarketCancelRequest request = new MarketCancelRequest(
            accountId, "player_cancel", "market-cancel-" + listingId, accountId);
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> unavailable = response(503, "busy");
        HttpResponse<String> conflict = response(409, "conflict");
        when(client.send(any(HttpRequest.class), anyStringBodyHandler()))
            .thenReturn(unavailable)
            .thenReturn(conflict);

        try (MockedStatic<ApiRequestUtil> api = mockApi(client)) {
            MarketRepository repository = new MarketRepository();
            assertThrows(MarketTransportException.class, () -> repository.cancel(listingId, request));
            MarketRequestRejectedException rejected = assertThrows(
                MarketRequestRejectedException.class,
                () -> repository.cancel(listingId, request));
            assertEquals(409, rejected.statusCode());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_4-統合フロー.md
     * 章・見出し: # 23_4-統合フロー > ## 3. 出品作成・cancel・売上受取
     * 検証契約: 出品作成の4xxは確定拒否、5xxは結果不明として保存境界処理へ返す。
     */
    @Test
    void createListingClassifiesOutcomeUnknownAndDeterministicHttpStatuses() throws Exception {
        UUID accountId = UUID.randomUUID();
        MarketListingCreateRequest request = new MarketListingCreateRequest(
            UUID.randomUUID(), accountId, List.of(new MarketListingSource(UUID.randomUUID(), 1L)),
            "material", "market_test_material", null, null, 1L, "gold", 100L, null, accountId
        );
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> unavailable = response(503, "busy");
        HttpResponse<String> conflict = response(409, "conflict");
        when(client.send(any(HttpRequest.class), anyStringBodyHandler()))
            .thenReturn(unavailable)
            .thenReturn(conflict);

        try (MockedStatic<ApiRequestUtil> api = mockApi(client)) {
            MarketRepository repository = new MarketRepository();
            assertThrows(MarketTransportException.class, () -> repository.createListing(request));
            MarketRequestRejectedException rejected = assertThrows(
                MarketRequestRejectedException.class,
                () -> repository.createListing(request));
            assertEquals(409, rejected.statusCode());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_4-統合フロー.md
     * 章・見出し: # 23_4-統合フロー > ## 3. 出品作成・cancel・売上受取
     * 検証契約: 出品APIの201成功本文が不正でも確定拒否にせず、同一操作の結果照会を要求する。
     */
    @Test
    void malformedListingSuccessRemainsOutcomeUnknown() throws Exception {
        UUID accountId = UUID.randomUUID();
        MarketListingCreateRequest request = new MarketListingCreateRequest(
            UUID.randomUUID(), accountId, List.of(new MarketListingSource(UUID.randomUUID(), 1L)),
            "material", "market_test_material", null, null, 1L, "gold", 100L, null, accountId);
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> invalid = response(201, "{}");
        when(client.send(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(invalid);
        try (MockedStatic<ApiRequestUtil> api = mockApi(client)) {
            assertThrows(MarketTransportException.class, () -> new MarketRepository().createListing(request));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_3-メソッド仕様.md
     * 章・見出し: # 23_3-メソッド仕様 > ## Cancel
     * 検証契約: 取消結果の404は未確定、200は厳密に検証したSQL receiptとして返す。
     */
    @Test
    void findCancelResultDistinguishesMissingAndCompletedReceipt() throws Exception {
        UUID listingId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID affectedEntryId = UUID.randomUUID();
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> missing = response(404, "");
        HttpResponse<String> completedResponse = response(200, cancelResponse(listingId, accountId, affectedEntryId));
        when(client.send(any(HttpRequest.class), anyStringBodyHandler()))
            .thenReturn(missing)
            .thenReturn(completedResponse);

        try (MockedStatic<ApiRequestUtil> api = mockApi(client)) {
            MarketRepository repository = new MarketRepository();
            assertTrue(repository.findCancelResult(listingId, accountId, "market-cancel-" + listingId).isEmpty());
            var completed = repository.findCancelResult(listingId, accountId, "market-cancel-" + listingId);
            assertTrue(completed.isPresent());
            assertEquals(List.of(affectedEntryId), completed.orElseThrow().affectedInventoryEntryIds());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_5-例外・ログ・運用.md
     * 章・見出し: # 23_5-例外・ログ・運用 > ## 例外方針
     * 検証契約: HTTP 200でも返却先entryを検証できない応答は結果不明として照会対象にする。
     */
    @Test
    void cancelRejectsMalformedSuccessAsOutcomeUnknown() throws Exception {
        UUID listingId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> malformed = response(200,
            "{\"listingId\":\"" + listingId + "\",\"sellerAccountId\":\"" + accountId
                + "\",\"status\":\"CANCELED\",\"canceledAt\":\"2026-09-09T00:00:00Z\"}");
        when(client.send(any(HttpRequest.class), anyStringBodyHandler()))
            .thenReturn(malformed);

        try (MockedStatic<ApiRequestUtil> api = mockApi(client)) {
            MarketRepository repository = new MarketRepository();
            MarketCancelRequest request = new MarketCancelRequest(
                accountId, "player_cancel", "market-cancel-" + listingId, accountId);
            assertThrows(MarketTransportException.class, () -> repository.cancel(listingId, request));
        }
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

    private static String cancelResponse(UUID listingId, UUID accountId, UUID affectedEntryId) {
        return "{\"listingId\":\"" + listingId
            + "\",\"sellerAccountId\":\"" + accountId
            + "\",\"status\":\"CANCELED\",\"canceledAt\":\"2026-09-09T00:00:00Z\""
            + ",\"affectedInventoryEntryIds\":[\"" + affectedEntryId + "\"]}";
    }

    private static MockedStatic<ApiRequestUtil> mockApi(HttpClient client) {
        MockedStatic<ApiRequestUtil> api = mockStatic(ApiRequestUtil.class);
        api.when(ApiRequestUtil::sharedClient).thenReturn(client);
        api.when(() -> ApiRequestUtil.buildRequestBuilder(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0, String.class);
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1" + path));
        });
        return api;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse.BodyHandler<String> anyStringBodyHandler() {
        return any(HttpResponse.BodyHandler.class);
    }

    private static HttpResponse<String> response(int status, String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
