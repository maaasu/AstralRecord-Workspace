package io.github.maaasu.astralrecordlobby;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

final class LobbyApiClient {
    private final Gson gson = new Gson();
    private final HttpClient client;
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;

    LobbyApiClient(FileConfiguration config) {
        baseUrl = config.getString("api.baseUrl", "http://127.0.0.1:5261").replaceAll("/+$", "");
        apiKey = config.getString("api.apiKey", "");
        timeout = Duration.ofMillis(Math.max(500, config.getInt("api.timeoutMillis", 3000)));
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL);
        if (config.getBoolean("api.allowInsecureTls", false)) {
            builder.sslContext(createInsecureSslContext());
        }
        client = builder.build();
    }

    Admission getAdmission(UUID uuid) {
        JsonObject json = gson.fromJson(send("GET", "/api/network/admissions/" + uuid, null), JsonObject.class);
        return new Admission(
            json.get("admitted").getAsBoolean(),
            json.get("permission").getAsInt(),
            json.has("denyReason") && !json.get("denyReason").isJsonNull()
                ? json.get("denyReason").getAsString() : null);
    }

    void publishDiscordChat(String serverId, String authorName, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("messageId", UUID.randomUUID().toString());
        body.addProperty("source", "discord");
        body.addProperty("sourceServerId", serverId);
        body.addProperty("authorName", authorName);
        body.addProperty("message", message);
        send("POST", "/api/network/chat", body.toString());
    }

    ChatBatch getMinecraftChat(long afterSequence) {
        JsonObject batch = gson.fromJson(
            send("GET", "/api/network/chat?source=minecraft&afterSequence=" + Math.max(0L, afterSequence), null),
            JsonObject.class);
        String generationId = batch.get("generationId").getAsString();
        JsonArray array = batch.getAsJsonArray("messages");
        List<ChatMessage> messages = new ArrayList<>();
        if (array == null) return new ChatBatch(generationId, messages);
        array.forEach(element -> {
            JsonObject value = element.getAsJsonObject();
            messages.add(new ChatMessage(
                value.get("sequence").getAsLong(),
                value.get("sourceServerId").getAsString(),
                value.get("authorName").getAsString(),
                value.get("message").getAsString()));
        });
        return new ChatBatch(generationId, messages);
    }

    /**
     * APIが保持する有効なサーバー状態を取得する。
     *
     * @return serverIdをキーとするサーバー状態。API側で期限切れの状態は含まれない
     * @throws IllegalStateException API通信またはレスポンス解析に失敗した場合
     */
    Map<String, ServerPresence> getServers() {
        JsonArray array = gson.fromJson(send("GET", "/api/network/servers", null), JsonArray.class);
        Map<String, ServerPresence> servers = new LinkedHashMap<>();
        if (array == null) return Map.of();
        array.forEach(element -> {
            JsonObject value = element.getAsJsonObject();
            ServerPresence presence = new ServerPresence(
                value.get("serverId").getAsString(),
                value.get("state").getAsString(),
                value.get("onlineCount").getAsInt(),
                value.get("capacity").getAsInt(),
                optionalInt(value, "donorExtraPlayers"),
                optionalInt(value, "adminExtraPlayers"));
            servers.put(presence.serverId().toLowerCase(java.util.Locale.ROOT), presence);
        });
        return Map.copyOf(servers);
    }

    /**
     * JSONの省略可能な非負整数を取得する。
     *
     * @param value 読み取り元JSON
     * @param name フィールド名
     * @return フィールド値。欠落またはnullの場合は0
     */
    private static int optionalInt(JsonObject value, String name) {
        return value.has(name) && !value.get(name).isJsonNull()
            ? Math.max(0, value.get(name).getAsInt()) : 0;
    }

    private String send(String method, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout)
            .header("X-Api-Key", apiKey)
            .header("Accept", "application/json");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Network API returned HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Network API request interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Network API request failed", exception);
        }
    }

    /**
     * 開発環境の自己署名証明書を受け入れるTLSコンテキストを生成する。
     *
     * <p>このコンテキストは証明書チェーンとホスト名を検証しないため、
     * {@code api.allowInsecureTls=true} が明示された場合だけ使用する。</p>
     *
     * @return 全証明書を信頼するTLSコンテキスト
     * @throws IllegalStateException TLSコンテキストを初期化できない場合
     */
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

    record Admission(boolean admitted, int permission, String denyReason) {
    }

    record ChatMessage(long sequence, String sourceServerId, String authorName, String message) {
    }

    record ChatBatch(String generationId, List<ChatMessage> messages) {
    }

    record ServerPresence(
        String serverId,
        String state,
        int onlineCount,
        int capacity,
        int donorExtraPlayers,
        int adminExtraPlayers
    ) {
        /**
         * 指定権限で利用できる追加枠を返す。
         *
         * @param permission APIユーザー権限
         * @return 寄付者・管理者が利用できる追加人数
         */
        int extraFor(int permission) {
            if (permission >= 99) {
                return (int) Math.min(Integer.MAX_VALUE,
                    (long) donorExtraPlayers + adminExtraPlayers);
            }
            if (permission >= 5) return donorExtraPlayers;
            return 0;
        }

        /**
         * 指定権限で利用できる最大人数を返す。
         *
         * @param permission APIユーザー権限
         * @return 基本上限と追加枠の合計
         */
        int limitFor(int permission) {
            return (int) Math.min(Integer.MAX_VALUE,
                (long) Math.max(0, capacity) + extraFor(permission));
        }

        /**
         * 指定権限の接続枠が満員か判定する。
         *
         * @param permission APIユーザー権限
         * @return 上限が無効、または現在人数が上限以上の場合true
         */
        boolean fullFor(int permission) {
            int limit = limitFor(permission);
            return limit <= 0 || Math.max(0, onlineCount) >= limit;
        }

        /**
         * API状態がオンラインか判定する。
         *
         * @return onlineの場合true
         */
        boolean online() {
            return "online".equalsIgnoreCase(state);
        }
    }
}
