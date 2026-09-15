package io.github.maaasu.astralRecord.feature.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import kotlin.Unit;

/** Network API経由でManagementDB正本のBAN状態を取得・更新します。 */
public final class NetworkBanClient {
    /**
     * 指定プレイヤーの現在のBAN状態を取得します。
     *
     * @param userUuid 対象プレイヤーUUID
     * @return ManagementDB正本とlegacy fallbackを反映したBAN状態
     * @throws IOException APIが成功応答または正しい応答を返さない場合
     * @throws InterruptedException 通信待機中に割り込まれた場合
     */
    public NetworkBanState get(UUID userUuid) throws IOException, InterruptedException {
        var request = ApiRequestUtil.buildRequestBuilder("/api/network/bans/" + userUuid).GET().build();
        var response = ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString());
        return parseSuccess(userUuid, response);
    }

    /**
     * 指定プレイヤーのBAN状態をrevision一致時だけ更新します。
     *
     * @param userUuid 対象プレイヤーUUID
     * @param actorUserUuid 実行者UUID。consoleはSystemUserのnil UUID
     * @param serverId 実行元RPGのnetwork.channelName
     * @param expectedRevision 直前GETで取得したrevision
     * @param banned BANを設定する場合は{@code true}
     * @param expiresAtUtc 有期限BANのUTC期限。無期限時は{@code null}
     * @param reason BAN理由。未指定時は{@code null}
     * @return 更新後のBAN状態
     * @throws IOException APIが成功応答または正しい応答を返さない場合
     * @throws InterruptedException 通信待機中に割り込まれた場合
     */
    public NetworkBanState update(
        UUID userUuid,
        UUID actorUserUuid,
        String serverId,
        int expectedRevision,
        boolean banned,
        Instant expiresAtUtc,
        String reason
    ) throws IOException, InterruptedException {
        String moderationKey = ConfigProperties.getInstance().getApiNetworkModerationKey();
        if (moderationKey.isBlank()) {
            throw new IOException("Network moderation credential is not configured");
        }
        String query = "?actor_user_uuid=" + encode(actorUserUuid.toString())
            + "&serverId=" + encode(serverId == null ? "" : serverId);
        String body = ApiRequestUtil.buildJsonBody(json -> {
            json.addProperty("expectedRevision", expectedRevision);
            json.addProperty("isBanned", banned);
            if (expiresAtUtc != null) {
                json.addProperty("expiresAtUtc", expiresAtUtc.toString());
            } else {
                json.add("expiresAtUtc", null);
            }
            if (reason != null) {
                json.addProperty("reason", reason);
            } else {
                json.add("reason", null);
            }
            return Unit.INSTANCE;
        });
        var request = ApiRequestUtil.buildRequestBuilder("/api/network/bans/" + userUuid + query)
            .header("X-Network-Moderation-Key", moderationKey)
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build();
        var response = ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString());
        return parseSuccess(userUuid, response);
    }

    private NetworkBanState parseSuccess(UUID requestedUuid, HttpResponse<String> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Network API returned HTTP " + response.statusCode());
        }
        try {
            JsonObject value = JsonParser.parseString(response.body()).getAsJsonObject();
            UUID userUuid = UUID.fromString(value.get("userUuid").getAsString());
            if (!requestedUuid.equals(userUuid)) {
                throw new IOException("Network API returned a different user UUID");
            }
            Instant expiresAtUtc = value.has("expiresAtUtc") && !value.get("expiresAtUtc").isJsonNull()
                ? Instant.parse(value.get("expiresAtUtc").getAsString())
                : null;
            String reason = value.has("reason") && !value.get("reason").isJsonNull()
                ? value.get("reason").getAsString()
                : null;
            return new NetworkBanState(
                userUuid,
                value.get("mcid").getAsString(),
                value.get("revision").getAsInt(),
                value.get("isBanned").getAsBoolean(),
                value.get("isActive").getAsBoolean(),
                value.get("isIndefinite").getAsBoolean(),
                expiresAtUtc,
                reason
            );
        } catch (RuntimeException exception) {
            throw new IOException("Network API returned an invalid ban response", exception);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
