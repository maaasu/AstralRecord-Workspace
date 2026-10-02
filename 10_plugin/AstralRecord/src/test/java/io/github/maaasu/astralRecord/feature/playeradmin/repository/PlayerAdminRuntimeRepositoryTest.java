package io.github.maaasu.astralRecord.feature.playeradmin.repository;

import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.net.URI;
import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class PlayerAdminRuntimeRepositoryTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/40-player-admin-edit/40_0-概要.md
     * 章・見出し: # 40_player-admin-edit 概要 > ## 編集開始と保存境界
     * 検証契約: 専用キーがなければ退避応答を送信せず、共通 API キーと異なる専用ヘッダーを付ける。
     */
    @Test
    void runtimeRequestsRequireDedicatedCredential() {
        ConfigProperties config = mock(ConfigProperties.class);
        when(config.getApiAuthApiKey()).thenReturn("common-secret");
        try (MockedStatic<ConfigProperties> configs = mockStatic(ConfigProperties.class);
             MockedStatic<ApiRequestUtil> requests = mockStatic(ApiRequestUtil.class)) {
            configs.when(ConfigProperties::getInstance).thenReturn(config);
            when(config.getApiPlayerAdminRuntimeKey()).thenReturn("");
            assertThrows(IllegalStateException.class,
                () -> PlayerAdminRuntimeRepository.buildRuntimeRequestBuilder("/api/player-admin/runtime/servers/rpg"));
            when(config.getApiPlayerAdminRuntimeKey()).thenReturn("common-secret");
            assertThrows(IllegalStateException.class,
                () -> PlayerAdminRuntimeRepository.buildRuntimeRequestBuilder("/api/player-admin/runtime/servers/rpg"));
            requests.verifyNoInteractions();

            when(config.getApiPlayerAdminRuntimeKey()).thenReturn("runtime-secret");
            requests.when(() -> ApiRequestUtil.buildRequestBuilder("/api/player-admin/runtime/servers/rpg"))
                .thenReturn(HttpRequest.newBuilder().uri(URI.create("https://api.example.test/runtime"))
                    .header("X-Api-Key", "common-secret"));
            HttpRequest request = PlayerAdminRuntimeRepository
                .buildRuntimeRequestBuilder("/api/player-admin/runtime/servers/rpg")
                .GET().build();
            assertEquals("common-secret", request.headers().firstValue("X-Api-Key").orElseThrow());
            assertEquals("runtime-secret",
                request.headers().firstValue("X-Player-Admin-Runtime-Key").orElseThrow());
        }
    }
}
