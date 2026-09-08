package io.github.maaasu.astralRecord.feature.loot.repository;

import io.github.maaasu.astralRecord.feature.loot.model.LootModel;
import io.github.maaasu.astralRecord.feature.loot.model.LootPoolModel;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LootRepositoryTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/06-loot/3-メソッド仕様/06_3-リポジトリ.md
     * 章・見出し: # 06_3-リポジトリ > ## 1. LootRepository メソッド仕様 > ### HTTPクライアント所有権
     * 検証契約: 一覧取得と単体取得は共有HTTPクライアントを借用し、処理完了時にcloseしない。
     */
    @Test
    void borrowsSharedHttpClientWithoutClosingIt() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> emptyTables = response(200, "[]");
        HttpResponse<String> emptyPools = response(200, "[]");
        HttpResponse<String> singleTable = response(
            200,
            "{\"schemaVersion\":1,\"id\":\"test\",\"pools\":[]}"
        );
        when(client.send(any(HttpRequest.class), anyStringBodyHandler()))
            .thenReturn(emptyTables)
            .thenReturn(emptyPools)
            .thenReturn(singleTable);

        List<LootModel> all;
        LootModel single;
        try (MockedStatic<ApiRequestUtil> api = mockApi(client);
             MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            LootRepository repository = new LootRepository();
            all = repository.findAll();
            single = repository.findById("test");
        }

        assertEquals(List.of(), all);
        assertEquals("test", single.getId());
        verify(client, never()).close();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/06-loot/3-メソッド仕様/06_3-リポジトリ.md
     * 章・見出し: # 06_3-リポジトリ > ## 2. JSON パース > ### ルートプールオブジェクトパース（内部補助）
     * 検証契約: pick未指定時はcontents件数、空contents時は0を使う。
     */
    @Test
    void missingPickDefaultsToContentCountIncludingEmptyPool() throws Exception {
        LootRepository repository = new LootRepository();
        Method parsePool = LootRepository.class.getDeclaredMethod("parsePool", String.class);
        parsePool.setAccessible(true);

        LootPoolModel populated = (LootPoolModel) parsePool.invoke(repository, """
            {"id":"populated","pick":null,"contents":[
              {"itemId":"item:first","rate":100,"amount":"1"},
              {"itemId":"item:second","rate":100,"amount":"1"}
            ]}
            """);
        LootPoolModel empty = (LootPoolModel) parsePool.invoke(
            repository,
            "{\"id\":\"empty\",\"contents\":[]}"
        );

        assertEquals(2, populated.getMinPick());
        assertEquals(2, populated.getMaxPick());
        assertEquals(0, empty.getMinPick());
        assertEquals(0, empty.getMaxPick());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/06-loot/3-メソッド仕様/06_3-リポジトリ.md
     * 章・見出し: # 06_3-リポジトリ > ## 2. JSON パース > ### ルートテーブルオブジェクトパース（内部補助）
     * 検証契約: 降順rangeを昇順へ正規化しrolls未指定を1〜1にする。
     */
    @Test
    void descendingRangesAreNormalizedAndMissingRollsDefaultToOne() throws Exception {
        LootRepository repository = new LootRepository();
        Method parsePool = LootRepository.class.getDeclaredMethod("parsePool", String.class);
        Method parseTable = LootRepository.class.getDeclaredMethod("parseTable", String.class);
        parsePool.setAccessible(true);
        parseTable.setAccessible(true);

        LootPoolModel pool = (LootPoolModel) parsePool.invoke(repository, """
            {"id":"range_pool","pick":"3~1","contents":[
              {"itemId":"item:first","rate":100},
              {"itemId":"item:second","rate":100},
              {"itemId":"item:third","rate":100}
            ]}
            """);
        Object descendingTable = parseTable.invoke(
            repository,
            "{\"id\":\"descending\",\"rolls\":\"2~0\",\"pools\":[]}"
        );
        Object defaultTable = parseTable.invoke(
            repository,
            "{\"id\":\"default\",\"pools\":[]}"
        );

        assertEquals(1, pool.getMinPick());
        assertEquals(3, pool.getMaxPick());
        assertEquals(0, readIntProperty(descendingTable, "getMinRolls"));
        assertEquals(2, readIntProperty(descendingTable, "getMaxRolls"));
        assertEquals(1, readIntProperty(defaultTable, "getMinRolls"));
        assertEquals(1, readIntProperty(defaultTable, "getMaxRolls"));
    }

    private int readIntProperty(Object target, String getterName) throws Exception {
        Method getter = target.getClass().getDeclaredMethod(getterName);
        getter.setAccessible(true);
        return (int) getter.invoke(target);
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
