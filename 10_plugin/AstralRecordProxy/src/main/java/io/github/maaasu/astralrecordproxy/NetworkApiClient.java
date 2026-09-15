package io.github.maaasu.astralrecordproxy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

final class NetworkApiClient {
    private final HttpClient client;
    private final Gson gson = new Gson();
    private final String baseUrl;
    private final String apiKey;
    private final String authoritySyncKey;
    private final Duration timeout;

    NetworkApiClient(ProxyConfig config) {
        baseUrl = config.apiBaseUrl().replaceAll("/+$", "");
        apiKey = config.apiKey();
        authoritySyncKey = config.authoritySyncKey();
        timeout = Duration.ofMillis(Math.max(500, config.apiTimeoutMillis()));
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL);
        if (config.allowInsecureTls()) {
            builder.sslContext(createInsecureSslContext());
        }
        client = builder.build();
    }

    CompletableFuture<Void> heartbeatPlayer(PlayerMetadata metadata) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", metadata.playerId().toString());
        body.addProperty("mcid", metadata.mcid());
        body.addProperty("serverId", metadata.serverId());
        body.addProperty("channel", metadata.channel());
        body.addProperty("displayName", metadata.displayName());
        if (metadata.level() == null) body.add("level", null); else body.addProperty("level", metadata.level());
        if (metadata.className() == null) body.add("className", null); else body.addProperty("className", metadata.className());
        body.addProperty("afk", metadata.afk());
        return send("PUT", "/api/network/players/" + metadata.playerId(), body.toString()).thenApply(ignored -> null);
    }

    CompletableFuture<Void> removePlayer(UUID playerId) {
        return send("DELETE", "/api/network/players/" + playerId, null).thenApply(ignored -> null);
    }

    /** Management DBを正本とするProxy設定を取得する。 */
    CompletableFuture<ManagedNetworkSettings> getManagedSettings() {
        return send("GET", "/api/network/settings", null)
            .thenApply(json -> ManagedNetworkSettings.fromJson(gson.fromJson(json, JsonObject.class)));
    }

    /** APIが未初期化の場合だけYAML旧値をManagement DBへbootstrapする。 */
    CompletableFuture<ManagedNetworkSettings> bootstrapManagedSettings(ManagedNetworkSettings legacySettings) {
        return send("POST", "/api/network/settings/bootstrap", legacySettings.toBootstrapJson().toString(), authoritySyncKey)
            .thenApply(json -> ManagedNetworkSettings.fromJson(gson.fromJson(json, JsonObject.class)));
    }

    /** 指定チャンネルへの接続可否とBAN状態を取得する。 */
    CompletableFuture<Admission> getAdmission(UUID playerId, String serverId) {
        String encodedServerId = URLEncoder.encode(serverId, StandardCharsets.UTF_8);
        return send("GET", "/api/network/admissions/" + playerId + "?serverId=" + encodedServerId, null)
            .thenApply(json -> Admission.fromJson(gson.fromJson(json, JsonObject.class)));
    }

    /** Management DBで現在有効なBAN一覧を取得する。 */
    CompletableFuture<List<BanState>> getActiveBans() {
        return send("GET", "/api/network/bans/active", null).thenApply(json -> {
            JsonArray values = gson.fromJson(json, JsonArray.class);
            List<BanState> result = new ArrayList<>();
            if (values != null) values.forEach(value -> result.add(BanState.fromJson(value.getAsJsonObject())));
            return List.copyOf(result);
        });
    }

    /**
     * サーバー人数と権限別定員をNetwork APIへ送信する。
     *
     * @param serverId backend識別子
     * @param displayName 表示名
     * @param online 現在接続人数
     * @param state backend到達状態
     * @param capacity 基本枠と権限別追加枠
     * @return API送信完了を表すfuture
     */
    CompletableFuture<Void> heartbeatServer(
        String serverId,
        String displayName,
        int online,
        String state,
        ProxyConfig.ServerCapacity capacity
    ) {
        JsonObject body = new JsonObject();
        body.addProperty("serverId", serverId);
        body.addProperty("displayName", displayName);
        body.addProperty("state", state);
        body.addProperty("onlineCount", online);
        body.addProperty("capacity", capacity.maxPlayers());
        body.addProperty("donorExtraPlayers", capacity.donorExtraPlayers());
        body.addProperty("adminExtraPlayers", capacity.adminExtraPlayers());
        return send("PUT", "/api/network/servers/" + serverId, body.toString()).thenApply(ignored -> null);
    }

    CompletableFuture<Void> publishMinecraftChat(BackendProtocol.Chat chat, String sourceServerId) {
        return send("POST", "/api/network/chat", minecraftChatRequest(chat, sourceServerId).toString())
            .thenApply(ignored -> null);
    }

    /**
     * MinecraftチャットのNetwork API登録payloadを生成する。
     *
     * @param chat backendから受信したチャット
     * @param sourceServerId 送信元backend ID
     * @return プレイヤー識別情報を含むAPI登録payload
     */
    static JsonObject minecraftChatRequest(BackendProtocol.Chat chat, String sourceServerId) {
        JsonObject body = new JsonObject();
        body.addProperty("messageId", chat.messageId().toString());
        body.addProperty("source", "minecraft");
        body.addProperty("sourceServerId", sourceServerId);
        body.addProperty("authorName", chat.displayName());
        body.addProperty("authorPlayerId", chat.playerId().toString());
        body.addProperty("authorMinecraftName", chat.authorName());
        body.addProperty(
            "message",
            chat.original().equals(chat.converted()) ? chat.original() : chat.original() + "[" + chat.converted() + "]"
        );
        body.addProperty("kind", "chat");
        return body;
    }

    /**
     * Minecraft由来のネットワーク接続通知を登録する。
     *
     * @param sourceServerId 通知発生元backend
     * @param message Discordへ表示する本文
     * @return API送信完了future
     */
    CompletableFuture<Void> publishLifecycleMessage(String sourceServerId, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("messageId", UUID.randomUUID().toString());
        body.addProperty("source", "minecraft");
        body.addProperty("sourceServerId", sourceServerId);
        body.addProperty("authorName", "AstralRecord");
        body.addProperty("message", message);
        body.addProperty("kind", "lifecycle");
        return send("POST", "/api/network/chat", body.toString()).thenApply(ignored -> null);
    }

    CompletableFuture<DiscordChatBatch> getDiscordChat(long afterSequence) {
        return send("GET", "/api/network/chat?source=discord&afterSequence=" + Math.max(0L, afterSequence), null)
            .thenApply(json -> {
                List<DiscordChat> messages = new ArrayList<>();
                JsonObject batch = gson.fromJson(json, JsonObject.class);
                String generationId = batch.get("generationId").getAsString();
                JsonArray array = batch.getAsJsonArray("messages");
                if (array == null) return new DiscordChatBatch(generationId, messages);
                array.forEach(element -> {
                    JsonObject value = element.getAsJsonObject();
                    messages.add(new DiscordChat(
                        value.get("sequence").getAsLong(),
                        value.get("messageId").getAsString(),
                        value.get("authorName").getAsString(),
                        value.get("message").getAsString()));
                });
                return new DiscordChatBatch(generationId, messages);
            });
    }

    private CompletableFuture<String> send(String method, String path, String body) {
        return send(method, path, body, null);
    }

    private CompletableFuture<String> send(String method, String path, String body, String syncKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout)
            .header("X-Api-Key", apiKey)
            .header("Accept", "application/json");
        if (syncKey != null && !syncKey.isBlank()) {
            builder.header("X-Authority-Sync-Key", syncKey);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new NetworkApiException(response.statusCode());
                }
                return response.body();
            });
    }

    private static SSLContext createInsecureSslContext() {
        TrustManager[] trustManagers = {new X509ExtendedTrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // 開発環境限定設定ではクライアント証明書を検証しない。
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // 開発環境限定設定ではサーバー証明書を検証しない。
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
                // 開発環境限定設定ではクライアント証明書を検証しない。
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
                // 開発環境限定設定ではサーバー証明書とホスト名を検証しない。
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
                // 開発環境限定設定ではクライアント証明書を検証しない。
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
                // 開発環境限定設定ではサーバー証明書とホスト名を検証しない。
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers, new SecureRandom());
            return context;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to initialize insecure TLS context", exception);
        }
    }

    record DiscordChat(long sequence, String messageId, String authorName, String message) {
    }

    record DiscordChatBatch(String generationId, List<DiscordChat> messages) {
    }

    record Admission(
        boolean admitted,
        String denyReason,
        int permission,
        boolean banIndefinite,
        OffsetDateTime banExpiresAtUtc,
        String banReason
    ) {
        static Admission fromJson(JsonObject value) {
            return new Admission(
                value.has("admitted") && value.get("admitted").getAsBoolean(),
                optionalText(value, "denyReason"),
                value.has("permission") ? value.get("permission").getAsInt() : 0,
                value.has("banIndefinite") && value.get("banIndefinite").getAsBoolean(),
                optionalDate(value, "banExpiresAtUtc"), optionalText(value, "banReason"));
        }
    }

    record BanState(UUID playerId, boolean active, boolean indefinite, OffsetDateTime expiresAtUtc, String reason) {
        static BanState fromJson(JsonObject value) {
            return new BanState(UUID.fromString(value.get("userUuid").getAsString()),
                value.has("isActive") && value.get("isActive").getAsBoolean(),
                value.has("isIndefinite") && value.get("isIndefinite").getAsBoolean(),
                optionalDate(value, "expiresAtUtc"), optionalText(value, "reason"));
        }
    }

    static boolean isNotFound(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && !(cause instanceof NetworkApiException)) cause = cause.getCause();
        return cause instanceof NetworkApiException apiFailure && apiFailure.statusCode == 404;
    }

    private static String optionalText(JsonObject value, String name) {
        return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString() : null;
    }

    private static OffsetDateTime optionalDate(JsonObject value, String name) {
        String raw = optionalText(value, name);
        return raw == null || raw.isBlank() ? null : OffsetDateTime.parse(raw);
    }

    private static final class NetworkApiException extends IllegalStateException {
        private final int statusCode;

        private NetworkApiException(int statusCode) {
            super("Network API returned HTTP " + statusCode);
            this.statusCode = statusCode;
        }
    }
}
