package io.github.maaasu.astralRecord.feature.mutation.repository;

import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateAcknowledgementException;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerStateRepositoryTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## player-state snapshot
     * 検証契約: POSTが結果不明のHTTP応答になった場合は同じsnapshotIdを照会し、COMPLETEDのACKを成功として返す。
     */
    @Test
    void recoversCompletedSnapshotAfterAmbiguousHttpResponse() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> unavailable = response(503, "busy");
        HttpResponse<String> completed = response(200,
            "{\"status\":\"COMPLETED\",\"ack\":{\"snapshotId\":\"" + snapshotId + "\"}}");
        when(client.send(any(HttpRequest.class), anyStringBodyHandler()))
            .thenReturn(unavailable)
            .thenReturn(completed);

        JsonObject acknowledgement;
        try (MockedStatic<ApiRequestUtil> api = mockApi(client)) {
            acknowledgement = new PlayerStateRepository().saveSnapshot(payload(snapshotId, accountId));
        }

        assertEquals(snapshotId.toString(), acknowledgement.get("snapshotId").getAsString());
        assertPostThenResultLookup(client, snapshotId, accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## player-state snapshot
     * 検証契約: 結果照会の404は失敗確定ではないため、同一payloadとsnapshotIdを再送してACKを確定する。
     */
    @Test
    void retriesIdenticalSnapshotWhenFirstResultLookupIsNotFound() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> unavailable = response(503, "busy");
        HttpResponse<String> notFound = response(404, "");
        HttpResponse<String> completed = response(200,
            "{\"snapshotId\":\"" + snapshotId + "\",\"accountId\":\"" + accountId + "\"}");
        when(client.send(any(HttpRequest.class), anyStringBodyHandler()))
            .thenReturn(unavailable)
            .thenReturn(notFound)
            .thenReturn(completed);

        JsonObject acknowledgement;
        try (MockedStatic<ApiRequestUtil> api = mockApi(client)) {
            acknowledgement = new PlayerStateRepository().saveSnapshot(payload(snapshotId, accountId));
        }

        assertEquals(snapshotId.toString(), acknowledgement.get("snapshotId").getAsString());
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(3)).send(requests.capture(), anyStringBodyHandler());
        assertEquals("POST", requests.getAllValues().get(0).method());
        assertEquals("GET", requests.getAllValues().get(1).method());
        assertEquals("POST", requests.getAllValues().get(2).method());
        assertEquals(requests.getAllValues().get(0).bodyPublisher().orElseThrow().contentLength(),
            requests.getAllValues().get(2).bodyPublisher().orElseThrow().contentLength());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## player-state snapshot
     * 検証契約: POSTのI/O例外でも失敗確定前に結果照会し、SQL確定済みACKがあれば重複操作せず成功扱いにする。
     */
    @Test
    void recoversCompletedSnapshotAfterPostIoFailure() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> completed = response(200,
            "{\"status\":\"COMPLETED\",\"ack\":{\"snapshotId\":\"" + snapshotId + "\"}}");
        when(client.send(any(HttpRequest.class), anyStringBodyHandler()))
            .thenThrow(new IOException("response lost"))
            .thenReturn(completed);

        JsonObject acknowledgement;
        try (MockedStatic<ApiRequestUtil> api = mockApi(client)) {
            acknowledgement = new PlayerStateRepository().saveSnapshot(payload(snapshotId, accountId));
        }

        assertEquals(snapshotId.toString(), acknowledgement.get("snapshotId").getAsString());
        assertPostThenResultLookup(client, snapshotId, accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## ACK検証と失敗処理
     * 検証契約: 結果照会の不正なHTTP 200は元の425へ戻さず、ACK契約不一致として上位へ伝播する。
     */
    @Test
    void propagatesMalformedCompletedLookupInsteadOfReturningOriginalTooEarlyResponse() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> tooEarly = response(425, "too early");
        HttpResponse<String> malformed = response(200, "{}");
        when(client.send(any(HttpRequest.class), anyStringBodyHandler()))
            .thenReturn(tooEarly)
            .thenReturn(malformed);

        try (MockedStatic<ApiRequestUtil> api = mockApi(client)) {
            assertThrows(PlayerStateAcknowledgementException.class,
                () -> new PlayerStateRepository().saveSnapshot(payload(snapshotId, accountId)));
        }

        assertPostThenResultLookup(client, snapshotId, accountId);
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

    private static String payload(UUID snapshotId, UUID accountId) {
        return "{\"snapshotId\":\"" + snapshotId + "\",\"accountId\":\"" + accountId + "\"}";
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

    private static void assertPostThenResultLookup(HttpClient client, UUID snapshotId, UUID accountId)
        throws Exception {
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(2)).send(requests.capture(), anyStringBodyHandler());
        assertEquals("POST", requests.getAllValues().get(0).method());
        assertEquals("GET", requests.getAllValues().get(1).method());
        assertEquals("/api/player-state/snapshots/" + snapshotId,
            requests.getAllValues().get(1).uri().getPath());
        assertEquals("accountId=" + accountId, requests.getAllValues().get(1).uri().getQuery());
    }
}
