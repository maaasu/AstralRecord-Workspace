package io.github.maaasu.astralRecord.feature.account.repository;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountManagementRepositoryTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-リポジトリ.md
     * 章・見出し: # 02_3-リポジトリ > ## 3. 管理APIの応答契約
     * 検証契約: 複製APIの201応答はaccountオブジェクト内のUUIDを使い、作成されたアカウントを返す。
     */
    @Test
    void cloneReadsTheAccountEnvelopeReturnedByTheApi() throws Exception {
        AccountRepository accounts = mock(AccountRepository.class);
        AccountModel created = mock(AccountModel.class);
        UUID createdId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        when(accounts.findByUuid(createdId)).thenReturn(created);
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = response(201,
            "{\"account\":{\"uuid\":\"" + createdId + "\"},\"replacedAccountId\":null}");
        doReturn(response).when(client).send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        try (MockedStatic<ApiRequestUtil> transport = mockStatic(ApiRequestUtil.class)) {
            transport.when(ApiRequestUtil::sharedClient).thenReturn(client);
            transport.when(() -> ApiRequestUtil.buildRequestBuilder(anyString())).thenAnswer(call ->
                HttpRequest.newBuilder(URI.create("https://example.invalid" + call.getArgument(0))));
            AccountModel result = new AccountManagementRepository(accounts).cloneAccount(
                sourceId, UUID.randomUUID(), 2, null, UUID.randomUUID());
            assertSame(created, result);
            verify(accounts).findByUuid(createdId);
            verify(client).send(argThat(request -> request.method().equals("POST")
                && request.uri().getPath().equals("/api/account/" + sourceId + "/clone")),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-リポジトリ.md
     * 章・見出し: # 02_3-リポジトリ > ## 3. 管理APIの応答契約
     * 検証契約: 確認後の複製先変更を示す409は成功結果として読まず、エラーコードを保持して呼出元へ返す。
     */
    @Test
    void conflictIsNotParsedAsAClonedAccount() throws Exception {
        AccountRepository accounts = mock(AccountRepository.class);
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = response(409, "{\"code\":\"TARGET_CHANGED\",\"message\":\"changed\"}");
        doReturn(response).when(client).send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        try (MockedStatic<ApiRequestUtil> transport = mockStatic(ApiRequestUtil.class)) {
            transport.when(ApiRequestUtil::sharedClient).thenReturn(client);
            transport.when(() -> ApiRequestUtil.buildRequestBuilder(anyString())).thenAnswer(call ->
                HttpRequest.newBuilder(URI.create("https://example.invalid" + call.getArgument(0))));
            var failure = assertThrows(AccountManagementRepository.AccountOperationException.class, () ->
                new AccountManagementRepository(accounts).cloneAccount(UUID.randomUUID(), UUID.randomUUID(),
                    1, UUID.randomUUID(), UUID.randomUUID()));
            assertEquals("TARGET_CHANGED", failure.code());
            verifyNoInteractions(accounts);
        }
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
