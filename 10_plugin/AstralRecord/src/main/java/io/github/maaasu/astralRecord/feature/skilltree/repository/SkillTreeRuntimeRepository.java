package io.github.maaasu.astralRecord.feature.skilltree.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryApiException;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 実ロード世代、サーバーsession、およびWeb由来スキルツリー操作を扱うPlugin専用API境界です。
 * Runtimeキー未設定時は通信せず、Web操作の受け付けを行いません。
 */
public final class SkillTreeRuntimeRepository {
    private static final String ROOT = "/api/skilltree/runtime/servers/";

    /** Webが登録した操作です。費用・条件・ポイントは含まず、Pluginが実ロード定義で再計算します。 */
    public record Operation(
            @NotNull UUID operationId,
            @NotNull UUID accountId,
            @NotNull String expectedDefinitionGenerationId,
            int expectedPlayerStateVersion,
            @NotNull String action,
            @NotNull String nodeId,
            @Nullable String sourceClassId,
            @NotNull String status,
            @Nullable String expectedEvaluationFingerprint
    ) {
    }

    /** sessionでfenceされた操作処理権限です。 */
    public record ClaimedOperation(@NotNull String leaseToken, @NotNull Operation operation) {
    }

    public boolean isConfigured() {
        return !ConfigProperties.getInstance().getApiSkillTreeRuntimeKey().isBlank();
    }

    /** 公開済みスナップショットを登録し、APIにもSHA-256照合を要求します。 */
    public void register(
            @NotNull String serverId,
            @NotNull UUID sessionId,
            @NotNull java.time.Instant serverStartedAtUtc,
            @NotNull String pluginVersion,
            @NotNull String compatibilityVersion,
            @NotNull String definitionGenerationId,
            @NotNull String canonicalSnapshotJson
    ) {
        JsonObject body = new JsonObject();
        body.addProperty("serverSessionId", sessionId.toString());
        body.addProperty("serverStartedAtUtc", serverStartedAtUtc.toString());
        body.addProperty("pluginVersion", pluginVersion);
        body.addProperty("compatibilityVersion", compatibilityVersion);
        body.addProperty("definitionGenerationId", definitionGenerationId);
        body.addProperty("canonicalSnapshotJson", canonicalSnapshotJson);
        body.addProperty("ready", true);
        send("PUT", path(serverId), body, 200);
    }

    /** 現sessionが実ロード済み世代を維持していることをTTL内で更新します。 */
    public void heartbeat(@NotNull String serverId, @NotNull UUID sessionId, @NotNull String definitionGenerationId) {
        JsonObject body = new JsonObject();
        body.addProperty("serverSessionId", sessionId.toString());
        body.addProperty("definitionGenerationId", definitionGenerationId);
        body.addProperty("ready", true);
        send("POST", path(serverId) + "/heartbeat", body, 200);
    }

    /** 現sessionが確定できるオンライン操作と、次回参加時のオフライン案を取得します。 */
    public @NotNull List<Operation> findPending(
            @NotNull String serverId,
            @NotNull UUID sessionId,
            @NotNull UUID accountId
    ) {
        String requestPath = path(serverId) + "/operations?server_session_id=" + sessionId + "&account_id=" + accountId;
        JsonArray values = send("GET", requestPath, null, 200).getAsJsonArray();
        List<Operation> result = new ArrayList<>();
        for (var element : values) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            try {
                result.add(new Operation(
                        UUID.fromString(value.get("operationId").getAsString()),
                        UUID.fromString(value.get("accountId").getAsString()),
                        value.get("expectedDefinitionGenerationId").getAsString(),
                        value.get("expectedPlayerStateVersion").getAsInt(),
                        value.get("action").getAsString(),
                        value.get("nodeId").getAsString(),
                        value.has("sourceClassId") && !value.get("sourceClassId").isJsonNull()
                                ? value.get("sourceClassId").getAsString() : null,
                        value.has("status") && !value.get("status").isJsonNull()
                                ? value.get("status").getAsString() : "PENDING",
                        value.has("expectedEvaluationFingerprint") && !value.get("expectedEvaluationFingerprint").isJsonNull()
                                ? value.get("expectedEvaluationFingerprint").getAsString() : null
                ));
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("Invalid skill tree runtime operation", invalid);
            }
        }
        return List.copyOf(result);
    }

    /** 操作を現在sessionへfenceし、同じ操作を別サーバーが確定できないようにします。 */
    public @Nullable ClaimedOperation claim(
            @NotNull String serverId,
            @NotNull UUID sessionId,
            @NotNull Operation operation
    ) {
        JsonObject body = new JsonObject();
        body.addProperty("serverSessionId", sessionId.toString());
        body.addProperty("accountId", operation.accountId().toString());
        try {
            JsonObject value = send("POST", path(serverId) + "/operations/" + operation.operationId() + "/claim", body, 200)
                    .getAsJsonObject();
            String leaseToken = value.get("leaseToken").getAsString();
            return new ClaimedOperation(leaseToken, operation);
        } catch (InventoryApiException conflict) {
            if (conflict.getStatusCode() == 409 || conflict.getStatusCode() == 404) return null;
            throw conflict;
        }
    }

    /**
     * Web が表示するための、Plugin が評価済みのプレイヤー別ビューを登録します。
     *
     * @param serverId 実行 backend 識別子
     * @param sessionId 現在の Plugin 起動 session
     * @param accountId 表示対象アカウント
     * @param playerView 実ロード定義・現在の状態・接続位置から生成したビュー
     */
    public void publishPlayerView(
            @NotNull String serverId,
            @NotNull UUID sessionId,
            @NotNull UUID accountId,
            @NotNull JsonObject playerView
    ) {
        JsonObject body = new JsonObject();
        body.addProperty("serverSessionId", sessionId.toString());
        body.addProperty("definitionGenerationId", playerView.get("definitionGenerationId").getAsString());
        body.addProperty("playerStateVersion", playerView.get("playerStateVersion").getAsInt());
        body.addProperty("editEligible", playerView.get("editEligible").getAsBoolean());
        body.add("view", playerView);
        send("PUT", path(serverId) + "/accounts/" + accountId + "/view", body, 200);
    }

    private @NotNull String path(@NotNull String serverId) {
        return ROOT + java.net.URLEncoder.encode(serverId, java.nio.charset.StandardCharsets.UTF_8);
    }

    private @NotNull com.google.gson.JsonElement send(
            @NotNull String method,
            @NotNull String requestPath,
            @Nullable JsonObject body,
            int expectedStatus
    ) {
        if (!isConfigured()) throw new IllegalStateException("Skill tree runtime API key is not configured.");
        try {
            HttpRequest.Builder builder = ApiRequestUtil.buildRequestBuilder(requestPath)
                    .header("X-SkillTree-Runtime-Key", ConfigProperties.getInstance().getApiSkillTreeRuntimeKey());
            HttpRequest request = switch (method) {
                case "GET" -> builder.GET().build();
                case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body.toString())).build();
                case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
                default -> throw new IllegalArgumentException("Unsupported method: " + method);
            };
            HttpResponse<String> response = ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != expectedStatus) {
                throw new InventoryApiException(method, requestPath, response.statusCode(), response.body());
            }
            return JsonParser.parseString(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Skill tree runtime request interrupted", interrupted);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }
}
