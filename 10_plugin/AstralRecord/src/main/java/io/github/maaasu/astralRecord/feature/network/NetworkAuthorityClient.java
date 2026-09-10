package io.github.maaasu.astralRecord.feature.network;

import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Network APIからProxy設定由来の最高権限UUIDを取得します。 */
final class NetworkAuthorityClient {
    /**
     * 現在のサーバー最高権限UUID一覧を取得します。
     *
     * @return 最高権限UUIDの重複なし集合
     * @throws IOException APIが成功応答を返さない場合
     * @throws InterruptedException 通信待機中に割り込まれた場合
     */
    Set<UUID> getAuthorities() throws IOException, InterruptedException {
        var request = ApiRequestUtil.buildRequestBuilder("/api/network/authorities").GET().build();
        var response = ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Network API returned HTTP " + response.statusCode());
        }
        return JsonParser.parseString(response.body()).getAsJsonArray().asList().stream()
            .map(element -> UUID.fromString(element.getAsString()))
            .collect(Collectors.toUnmodifiableSet());
    }
}
