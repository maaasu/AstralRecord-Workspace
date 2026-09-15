package io.github.maaasu.astralRecord.feature.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Network APIからチャンネル単位の接続・運営ロールを取得します。 */
final class NetworkChannelAccessClient {
    /**
     * 指定プレイヤーのチャンネルロールを取得します。
     *
     * @param userUuid 判定対象のプレイヤーUUID
     * @param serverId RPGのnetwork.channelName
     * @return APIが返したチャンネルロール
     * @throws IOException APIが成功応答または正しい応答を返さない場合
     * @throws InterruptedException 通信待機中に割り込まれた場合
     */
    NetworkChannelAccess getAccess(UUID userUuid, String serverId) throws IOException, InterruptedException {
        String encodedServerId = URLEncoder.encode(serverId == null ? "" : serverId, StandardCharsets.UTF_8);
        String path = "/api/network/channel-access/" + userUuid + "?serverId=" + encodedServerId;
        var request = ApiRequestUtil.buildRequestBuilder(path).GET().build();
        var response = ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Network API returned HTTP " + response.statusCode());
        }

        try {
            JsonObject value = JsonParser.parseString(response.body()).getAsJsonObject();
            UUID responseUserUuid = UUID.fromString(value.get("userUuid").getAsString());
            if (!userUuid.equals(responseUserUuid)) {
                throw new IOException("Network API returned a different user UUID");
            }
            return new NetworkChannelAccess(
                responseUserUuid,
                value.get("serverId").getAsString(),
                value.get("channelKnown").getAsBoolean(),
                value.get("isAuthority").getAsBoolean(),
                value.get("debugUser").getAsBoolean(),
                value.get("whitelisted").getAsBoolean(),
                value.get("whitelistEnabled").getAsBoolean(),
                value.get("allowed").getAsBoolean(),
                value.get("permission").getAsInt()
            );
        } catch (RuntimeException exception) {
            throw new IOException("Network API returned an invalid channel access response", exception);
        }
    }
}
