package io.github.maaasu.astralRecord.feature.playeradmin.repository;

import com.sun.net.httpserver.HttpServer;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class PlayerAdminRuntimeRepositoryTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/40-player-admin-edit/40_0-概要.md
     * 章・見出し: # 40_player-admin-edit 概要 > ## 編集開始と保存境界
     * 検証契約: runtime登録・退避取得・ACKは専用キーの設定なしで共通APIキーを送信する。
     */
    @Test
    void runtimeRequestsUseCommonApiKeyWithoutDedicatedConfiguration() throws Exception {
        List<ObservedRequest> received = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            received.add(new ObservedRequest(exchange.getRequestMethod(), path,
                exchange.getRequestHeaders().getFirst("X-Api-Key"),
                exchange.getRequestHeaders().getFirst("X-Player-Admin-Runtime-Key")));
            byte[] response = (path.endsWith("/drains") ? "[]" : "{}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        ConfigProperties config = mock(ConfigProperties.class);
        when(config.getApiBaseUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        when(config.getApiAuthApiKey()).thenReturn("common-secret");
        when(config.getApiTimeout()).thenReturn(5000);
        when(config.isApiSslVerifyEnabled()).thenReturn(true);
        try (MockedStatic<ConfigProperties> configs = mockStatic(ConfigProperties.class);
             MockedStatic<ApiRequestUtil> requests = mockStatic(ApiRequestUtil.class)) {
            configs.when(ConfigProperties::getInstance).thenReturn(config);
            requests.when(() -> ApiRequestUtil.buildRequestBuilder(anyString())).thenCallRealMethod();
            requests.when(ApiRequestUtil::sharedClient).thenReturn(HttpClient.newHttpClient());
            PlayerAdminRuntimeRepository repository = new PlayerAdminRuntimeRepository();
            UUID boot = UUID.randomUUID();
            PlayerAdminRuntimeRepository.Drain drain = new PlayerAdminRuntimeRepository.Drain(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "DRAINING", 1);

            repository.register("dev", boot, "item-hash", "class-hash");
            assertEquals(List.of(), repository.findActive("dev", boot));
            repository.acknowledge("dev", boot, drain, UUID.randomUUID());

            assertEquals(List.of(
                new ObservedRequest("PUT", "/api/player-admin/runtime/servers/dev", "common-secret", null),
                new ObservedRequest("GET", "/api/player-admin/runtime/servers/dev/drains", "common-secret", null),
                new ObservedRequest("POST", "/api/player-admin/runtime/edit-sessions/" + drain.editSessionId()
                    + "/drain-ack", "common-secret", null)), received);
        } finally {
            server.stop(0);
        }
    }

    private record ObservedRequest(String method, String path, String apiKey, String runtimeKey) { }
}
