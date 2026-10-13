package io.github.maaasu.astralRecord.feature.skilltree.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    private volatile long batchRetryAtNanos;
    private volatile long ackRetryAtNanos;
    private static final long CAPABILITY_RETRY_NANOS = java.time.Duration.ofMinutes(1).toNanos();

    /** ログイン毎の処理権限。秘密値はAPIの照合にだけ使用し、画面やログへ出さない。 */
    public record AccountSession(UUID id, String token) {
        /** 新しいログイン用の再利用しないsessionを生成する。 */
        public static AccountSession create() {
            byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes);
            return new AccountSession(UUID.randomUUID(), java.util.HexFormat.of().formatHex(bytes));
        }
    }

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
            @Nullable String expectedEvaluationFingerprint,
            @NotNull List<Change> changes
    ) {
    }

    /** BATCH 要求に含まれる順序付きノード変更です。 */
    public record Change(@NotNull String action, @NotNull String nodeId, @Nullable String sourceClassId) {
    }

    /** sessionでfenceされた操作処理権限です。 */
    public record ClaimedOperation(@NotNull String leaseToken, @NotNull Operation operation) {
    }

    /** ログイン時の保存状態と公開済み定義の照合結果。 */
    public enum LoginPatchStatus {
        CURRENT, UPDATE_REQUIRED, APPLIED, CHANNEL_OUTDATED, UNPUBLISHED, INCOMPATIBLE, LEGACY_REQUIRES_REPAIR
    }

    /** preview または apply の結果。解除対象と stateVersion は apply の確認に使う。 */
    public record LoginPatchResult(@NotNull LoginPatchStatus status, @NotNull String definitionGenerationId,
                                    int stateVersion, @NotNull List<String> removedNodeIds) {
    }

    public boolean isConfigured() {
        return !ConfigProperties.getInstance().getApiSkillTreeRuntimeKey().isBlank();
    }

    /** 公開済みスナップショットを登録し、APIにもSHA-256照合を要求します。 */
    public void register(
            @NotNull String serverId,
            @NotNull UUID sessionId,
            @NotNull java.time.Instant serverStartedAtUtc,
            long publicationRevision,
            @NotNull String pluginVersion,
            @NotNull String compatibilityVersion,
            @NotNull String definitionGenerationId,
            @NotNull String canonicalSnapshotJson
    ) {
        JsonObject body = new JsonObject();
        body.addProperty("serverSessionId", sessionId.toString());
        body.addProperty("serverStartedAtUtc", serverStartedAtUtc.toString());
        body.addProperty("publicationRevision", publicationRevision);
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
        return parseOperations(values);
    }

    /** APIの操作配列を既存の操作モデルへ変換し、不正な操作形式は例外で拒否する。 */
    private static List<Operation> parseOperations(JsonArray values) {
        List<Operation> result = new ArrayList<>();
        for (var element : values) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            try {
                List<Change> changes = new ArrayList<>();
                if (value.has("changes") && value.get("changes").isJsonArray()) {
                    for (var changeElement : value.getAsJsonArray("changes")) {
                        JsonObject change = changeElement.getAsJsonObject();
                        changes.add(new Change(change.get("action").getAsString(), change.get("nodeId").getAsString(),
                                change.has("sourceClassId") && !change.get("sourceClassId").isJsonNull()
                                        ? change.get("sourceClassId").getAsString() : null));
                    }
                }
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
                                ? value.get("expectedEvaluationFingerprint").getAsString() : null,
                        List.copyOf(changes)
                ));
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("Invalid skill tree runtime operation", invalid);
            }
        }
        return List.copyOf(result);
    }

    /** 既存sessionの更新と任意のview公開をまとめる1件。秘密tokenをログへ出しません。 */
    public record AccountHeartbeat(UUID accountId, AccountSession session, String generationId,
                                   @Nullable JsonObject view, long sequence, boolean viewUnchanged) {
        /** 診断では対象accountだけを示し、session tokenとview内容を公開しない。 */
        @Override public String toString() { return "AccountHeartbeat[accountId=" + accountId + "]"; }
    }

    /** accountごとの更新ACKとpending操作。view拒否の場合は次回全体viewを再送します。 */
    public record AccountHeartbeatResult(boolean renewed, boolean viewAccepted, List<Operation> operations) { }

    /**
     * 最大32件の既存sessionの更新・view公開・pending照会を1回のHTTP要求にまとめます。
     *
     * @param serverId 実行backend識別子
     * @param bootId 現在のPlugin起動session
     * @param items 重複accountを含まない1〜32件。未変更viewはACK済み全体との一致を呼出側で確認する
     * @return 入力accountすべてのACKと操作。失効sessionはrenewed=false
     * @throws RuntimeException 通信失敗またはACK件数・識別子・形式の不一致
     */
    public java.util.Map<UUID, AccountHeartbeatResult> heartbeatAccounts(String serverId, UUID bootId,
                                                                       List<AccountHeartbeat> items) {
        if (items.isEmpty() || items.size() > 32 || items.stream().map(AccountHeartbeat::accountId).distinct().count() != items.size())
            throw new IllegalArgumentException("Invalid skill tree heartbeat batch");
        if (batchRetryAtNanos != 0 && System.nanoTime() - batchRetryAtNanos < 0)
            return heartbeatAccountsLegacy(serverId, bootId, items);
        JsonArray requests = new JsonArray();
        for (AccountHeartbeat item : items) {
            JsonObject request = new JsonObject();
            request.addProperty("accountId", item.accountId().toString());
            JsonObject session = new JsonObject();
            session.addProperty("serverSessionId", bootId.toString());
            session.addProperty("definitionGenerationId", item.generationId());
            addAccountSession(session, item.session());
            request.add("session", session);
            if (item.view() != null) {
                JsonObject view = viewRequest(bootId, item.view(), item.session(), item.sequence());
                if (item.viewUnchanged()) view.remove("view");
                request.add("view", view);
                request.addProperty("viewUnchanged", item.viewUnchanged());
                if (item.viewUnchanged()) request.addProperty("viewContentHash", viewContentHash(item.view()));
            }
            requests.add(request);
        }
        JsonObject body = new JsonObject(); body.add("items", requests);
        JsonArray response;
        try { response = send("POST", path(serverId) + "/accounts/heartbeat", body, 200).getAsJsonArray(); }
        catch (InventoryApiException unavailable) {
            if (!unsupportedEndpoint(unavailable)) throw unavailable;
            batchRetryAtNanos = System.nanoTime() + CAPABILITY_RETRY_NANOS;
            return heartbeatAccountsLegacy(serverId, bootId, items);
        }
        batchRetryAtNanos = 0;
        java.util.Map<UUID, AccountHeartbeatResult> results = new java.util.HashMap<>();
        for (var element : response) {
            JsonObject value = element.getAsJsonObject();
            UUID accountId = UUID.fromString(value.get("accountId").getAsString());
            if (items.stream().noneMatch(item -> item.accountId().equals(accountId)) || results.containsKey(accountId))
                throw new IllegalStateException("Invalid skill tree heartbeat response");
            results.put(accountId, new AccountHeartbeatResult(value.get("renewed").getAsBoolean(),
                    value.get("viewAccepted").getAsBoolean(), parseOperations(value.getAsJsonArray("operations"))));
        }
        if (results.size() != items.size()) throw new IllegalStateException("Incomplete skill tree heartbeat response");
        return java.util.Map.copyOf(results);
    }

    /** 未対応APIでは既存の処理権限更新・全体view・pending経路を使い、同じACKモデルへ変換する。 */
    private java.util.Map<UUID, AccountHeartbeatResult> heartbeatAccountsLegacy(String serverId, UUID bootId, List<AccountHeartbeat> items) {
        java.util.Map<UUID, AccountHeartbeatResult> results = new java.util.HashMap<>();
        for (var item : items) {
            boolean renewed = acquireAccount(serverId, bootId, item.accountId(), item.session(), item.generationId());
            boolean accepted = false;
            if (renewed && item.view() != null) {
                try { publishPlayerView(serverId, bootId, item.accountId(), item.view(), item.session(), item.sequence()); accepted = true; }
                catch (InventoryApiException conflict) { if (conflict.getStatusCode() != 409) throw conflict; }
            }
            results.put(item.accountId(), new AccountHeartbeatResult(renewed, accepted,
                    renewed ? findPending(serverId, bootId, item.accountId()) : List.of()));
        }
        return java.util.Map.copyOf(results);
    }

    /** 権限拒否や通信障害と区別し、旧APIの未対応経路だけを判定する。 */
    private static boolean unsupportedEndpoint(InventoryApiException failure) {
        return failure.getStatusCode() == 404 || failure.getStatusCode() == 405;
    }

    /** 保存済みviewとの全体一致照合に使うUTF-8 JSONのSHA-256を生成する。 */
    private static String viewContentHash(JsonObject view) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(view.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    /** 操作を現在sessionへfenceし、同じ操作を別サーバーが確定できないようにします。 */
    public @Nullable ClaimedOperation claim(
            @NotNull String serverId,
            @NotNull UUID sessionId,
            @NotNull Operation operation,
            @NotNull AccountSession accountSession
    ) {
        JsonObject body = new JsonObject();
        body.addProperty("serverSessionId", sessionId.toString());
        body.addProperty("accountId", operation.accountId().toString());
        addAccountSession(body, accountSession);
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
     * @param accountSession 現在のログインで取得した処理権限
     * @param sequence 単調増加するview番号。APIは遅延した番号を拒否する
     * @throws RuntimeException 通信失敗または登録拒否。ACK未対応の404/405だけ旧view経路へ戻る
     */
    public void publishPlayerView(
            @NotNull String serverId,
            @NotNull UUID sessionId,
            @NotNull UUID accountId,
            @NotNull JsonObject playerView,
            @NotNull AccountSession accountSession,
            long sequence
    ) {
        JsonObject body = viewRequest(sessionId, playerView, accountSession, sequence);
        String viewPath = path(serverId) + "/accounts/" + accountId + "/view";
        if (ackRetryAtNanos == 0 || System.nanoTime() - ackRetryAtNanos >= 0) {
            try { send("PUT", viewPath + "/ack", body, 200); ackRetryAtNanos = 0; return; }
            catch (InventoryApiException unavailable) {
                if (!unsupportedEndpoint(unavailable)) throw unavailable;
                ackRetryAtNanos = System.nanoTime() + CAPABILITY_RETRY_NANOS;
            }
        }
        send("PUT", viewPath, body, 200);
    }

    /** 現sessionの処理権限を取得・更新する。期限切れまたは終了済みIDは取得し直さない。 */
    public boolean acquireAccount(String serverId, UUID bootId, UUID accountId, AccountSession session, String generationId) {
        JsonObject body = new JsonObject();
        body.addProperty("serverSessionId", bootId.toString());
        body.addProperty("definitionGenerationId", generationId);
        addAccountSession(body, session);
        try { send("POST", path(serverId) + "/accounts/" + accountId + "/sessions", body, 200); return true; }
        catch (InventoryApiException failure) { if (failure.getStatusCode() == 409) return false; throw failure; }
    }

    /**
     * 現在のアカウント session でログイン時パッチを照合・適用します。通常の状態読込より先に呼びます。
     *
     * @param serverId 実行 backend 識別子
     * @param bootId 現在の Plugin 起動 session
     * @param accountId 対象アカウント
     * @param session 現ログインの account session
     * @param generationId このサーバーがロードした定義世代
     * @param apply true の場合だけ UPDATE_REQUIRED のパッチを確定する
     * @param expectedStateVersion preview で返された状態版。preview 時は使用しない
     * @param removeNodeIds preview で確認した解除対象。apply 時に API が再検証する
     * @return 公開定義と保存状態の照合結果
     * @throws RuntimeException API 通信失敗または不正な応答
     */
    public @NotNull LoginPatchResult loginPatch(String serverId, UUID bootId, UUID accountId,
                                                AccountSession session, String generationId,
                                                boolean apply, int expectedStateVersion, List<String> removeNodeIds) {
        JsonObject body = new JsonObject();
        body.addProperty("serverSessionId", bootId.toString());
        addAccountSession(body, session);
        body.addProperty("definitionGenerationId", generationId);
        body.addProperty("apply", apply);
        if (apply) body.addProperty("expectedStateVersion", expectedStateVersion);
        JsonArray requestedRemovals = new JsonArray();
        removeNodeIds.forEach(requestedRemovals::add);
        body.add("removeNodeIds", requestedRemovals);
        JsonObject response = send("POST", path(serverId) + "/accounts/" + accountId + "/login-patch", body, 200)
                .getAsJsonObject();
        try {
            List<String> confirmedRemovals = new ArrayList<>();
            response.getAsJsonArray("removedNodeIds").forEach(node -> confirmedRemovals.add(node.getAsString()));
            return new LoginPatchResult(
                    LoginPatchStatus.valueOf(response.get("status").getAsString()),
                    response.get("definitionGenerationId").getAsString(),
                    response.get("stateVersion").getAsInt(), List.copyOf(confirmedRemovals));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Invalid skill tree login patch response", invalid);
        }
    }

    /** 退出保存の完了後に同じsessionだけを閉じ、検証済みの最終viewをオフライン案の基準に残す。 */
    public void closeAccount(String serverId, UUID bootId, UUID accountId, AccountSession session, JsonObject view, long sequence) {
        send("POST", path(serverId) + "/accounts/" + accountId + "/session-close", viewRequest(bootId, view, session, sequence), 200);
    }

    private JsonObject viewRequest(UUID sessionId, JsonObject playerView, AccountSession accountSession, long sequence) {
        JsonObject body = new JsonObject();
        body.addProperty("serverSessionId", sessionId.toString());
        addAccountSession(body, accountSession);
        body.addProperty("viewSequence", sequence);
        body.addProperty("definitionGenerationId", playerView.has("definitionGenerationId") ? playerView.get("definitionGenerationId").getAsString() : "");
        body.addProperty("playerStateVersion", playerView.has("playerStateVersion") ? playerView.get("playerStateVersion").getAsInt() : 0);
        body.addProperty("evaluationFingerprint", playerView.has("evaluationFingerprint") ? playerView.get("evaluationFingerprint").getAsString() : "");
        body.addProperty("editEligible", playerView.has("editEligible") && playerView.get("editEligible").getAsBoolean());
        body.add("view", playerView);
        return body;
    }

    private static void addAccountSession(JsonObject body, AccountSession session) {
        body.addProperty("accountSessionId", session.id().toString());
        body.addProperty("accountLeaseToken", session.token());
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
