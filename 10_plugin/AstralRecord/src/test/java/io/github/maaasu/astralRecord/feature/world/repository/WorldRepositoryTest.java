package io.github.maaasu.astralRecord.feature.world.repository;

import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorldRepositoryTest {

    @Test
    void parsesEveryWorldFromSingleListRequest() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpRequest.Builder builder = mock(HttpRequest.Builder.class);
        HttpRequest request = mock(HttpRequest.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(builder.GET()).thenReturn(builder);
        when(builder.build()).thenReturn(request);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
            [
              {"id":"hub_lobby","displayName":"ロビー","worldType":"HUB"},
              {"id":"skill_tree","displayName":"スキルツリー","worldType":"SKILL_TREE"}
            ]
            """);
        when(client.send(
            eq(request),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        )).thenReturn(response);

        List<WorldMasterData> worlds;
        try (MockedStatic<ApiRequestUtil> api = mockStatic(ApiRequestUtil.class);
             MockedStatic<Logger> logger = mockStatic(Logger.class)) {
            api.when(ApiRequestUtil::buildClient).thenReturn(client);
            api.when(() -> ApiRequestUtil.buildRequestBuilder("/api/world")).thenReturn(builder);

            worlds = new WorldRepository().findAll();

            api.verify(ApiRequestUtil::buildClient, times(1));
            api.verify(() -> ApiRequestUtil.buildRequestBuilder("/api/world"), times(1));
        }

        assertEquals(List.of("hub_lobby", "skill_tree"), worlds.stream().map(WorldMasterData::id).toList());
        verify(client, times(1)).send(
            eq(request),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        );
    }
}
