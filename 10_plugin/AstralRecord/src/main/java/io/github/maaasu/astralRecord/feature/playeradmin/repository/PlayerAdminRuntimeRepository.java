package io.github.maaasu.astralRecord.feature.playeradmin.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 管理編集のサーバー参加登録、退避要求、確定通知を扱う API 境界です。 */
public final class PlayerAdminRuntimeRepository {
    private static final String ROOT = "/api/player-admin/runtime";

    /** 現在の起動セッションに紐づく管理編集要求です。 */
    public record Drain(UUID editSessionId, UUID accountId, UUID userUuid, String status, long revision) { }

    /** 現在のサーバー起動を管理編集の参加対象として登録します。 */
    public void register(@NotNull String serverId, @NotNull UUID serverSessionId,
                         @NotNull String itemCatalogHash, @NotNull String classCatalogHash) {
        JsonObject body = new JsonObject();
        body.addProperty("serverSessionId", serverSessionId.toString());
        body.addProperty("role", "RPG");
        body.addProperty("itemCatalogHash", itemCatalogHash);
        body.addProperty("classCatalogHash", classCatalogHash);
        send("PUT", ROOT + "/servers/" + encoded(serverId), body);
    }

    /** 現在の起動に割り当てられた有効な管理編集要求を取得します。 */
    public @NotNull List<Drain> findActive(@NotNull String serverId, @NotNull UUID serverSessionId) {
        JsonArray values = send("GET", ROOT + "/servers/" + encoded(serverId)
            + "/drains?server_session_id=" + serverSessionId, null).getAsJsonArray();
        List<Drain> drains = new ArrayList<>();
        for (var element : values) {
            JsonObject value = element.getAsJsonObject();
            drains.add(new Drain(
                UUID.fromString(value.get("editSessionId").getAsString()),
                UUID.fromString(value.get("accountId").getAsString()),
                UUID.fromString(value.get("userUuid").getAsString()),
                value.get("status").getAsString(),
                value.get("revision").getAsLong()
            ));
        }
        return List.copyOf(drains);
    }

    /** 保存・退出・処理セッション終了を確認できた場合だけ退避完了を通知します。 */
    public void acknowledge(@NotNull String serverId, @NotNull UUID serverSessionId,
                            @NotNull Drain drain, @NotNull UUID ackId) {
        JsonObject body = new JsonObject();
        body.addProperty("serverId", serverId);
        body.addProperty("serverSessionId", serverSessionId.toString());
        body.addProperty("accountId", drain.accountId().toString());
        body.addProperty("userUuid", drain.userUuid().toString());
        body.addProperty("saved", true);
        body.addProperty("offline", true);
        body.addProperty("ackId", ackId.toString());
        send("POST", ROOT + "/edit-sessions/" + drain.editSessionId() + "/drain-ack", body);
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static com.google.gson.JsonElement send(String method, String path, JsonObject body) {
        try {
            HttpRequest.Builder builder = buildRuntimeRequestBuilder(path);
            HttpRequest request = switch (method) {
                case "GET" -> builder.GET().build();
                case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body.toString())).build();
                case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
                default -> throw new IllegalArgumentException("Unsupported method");
            };
            HttpResponse<String> response = ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Player admin runtime API returned HTTP " + response.statusCode());
            }
            return JsonParser.parseString(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Player admin runtime API interrupted", interrupted);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    static HttpRequest.Builder buildRuntimeRequestBuilder(String path) {
        ConfigProperties config = ConfigProperties.getInstance();
        String runtimeKey = config.getApiPlayerAdminRuntimeKey();
        if (runtimeKey == null || runtimeKey.isBlank()) {
            throw new IllegalStateException("api.playerAdminRuntimeKey is required");
        }
        if (runtimeKey.equals(config.getApiAuthApiKey())) {
            throw new IllegalStateException("api.playerAdminRuntimeKey must differ from the common API key");
        }
        return ApiRequestUtil.buildRequestBuilder(path)
            .header("X-Player-Admin-Runtime-Key", runtimeKey);
    }
}
