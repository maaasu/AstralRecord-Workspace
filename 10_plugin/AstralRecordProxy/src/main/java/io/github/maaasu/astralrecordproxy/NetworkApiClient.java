package io.github.maaasu.astralrecordproxy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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
    private final Duration timeout;

    NetworkApiClient(ProxyConfig config) {
        baseUrl = config.apiBaseUrl().replaceAll("/+$", "");
        apiKey = config.apiKey();
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

    CompletableFuture<Void> heartbeatServer(String serverId, String displayName, int online, int capacity) {
        JsonObject body = new JsonObject();
        body.addProperty("serverId", serverId);
        body.addProperty("displayName", displayName);
        body.addProperty("state", "online");
        body.addProperty("onlineCount", online);
        body.addProperty("capacity", capacity);
        return send("PUT", "/api/network/servers/" + serverId, body.toString()).thenApply(ignored -> null);
    }

    CompletableFuture<Void> publishMinecraftChat(BackendProtocol.Chat chat, String sourceServerId) {
        JsonObject body = new JsonObject();
        body.addProperty("messageId", chat.messageId().toString());
        body.addProperty("source", "minecraft");
        body.addProperty("sourceServerId", sourceServerId);
        body.addProperty("authorName", chat.displayName());
        body.addProperty("message", chat.message());
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
        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("Network API returned HTTP " + response.statusCode());
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
}
