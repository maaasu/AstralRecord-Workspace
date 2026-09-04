package io.github.maaasu.astralrecordlobby;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
        client = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
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

    record Admission(boolean admitted, int permission, String denyReason) {
    }

    record ChatMessage(long sequence, String sourceServerId, String authorName, String message) {
    }

    record ChatBatch(String generationId, List<ChatMessage> messages) {
    }
}
