package io.github.maaasu.astralrecordgeyser;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses the public session server independently of AstralRecord's API connection. */
final class MojangSkinClient implements AutoCloseable {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(REQUEST_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    private final AtomicBoolean rateLimited = new AtomicBoolean();

    String fetch(UUID uuid) throws IOException, InterruptedException {
        if (rateLimited.get()) return null;
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                "https://sessionserver.mojang.com/session/minecraft/profile/" + compact(uuid)))
            .timeout(REQUEST_TIMEOUT).header("Accept", "application/json").GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return switch (response.statusCode()) {
            case 200 -> parse(uuid, response.body());
            case 204, 404 -> null;
            case 429 -> {
                rateLimited.set(true);
                throw new IOException("Mojang session server rate limited requests (HTTP 429)");
            }
            default -> throw new IOException("Mojang session server returned HTTP " + response.statusCode());
        };
    }

    static String parse(UUID expected, String json) {
        try {
            JsonObject profile = JsonParser.parseString(json).getAsJsonObject();
            requireProfileId(expected, HeadCatalog.requireString(profile.get("id")));
            String payload = null;
            for (JsonElement element : profile.getAsJsonArray("properties")) {
                JsonObject property = element.getAsJsonObject();
                if (!"textures".equals(HeadCatalog.requireString(property.get("name")))) continue;
                if (payload != null) throw new IllegalArgumentException("Duplicate textures property");
                payload = HeadCatalog.requireString(property.get("value"));
            }
            if (payload == null) throw new IllegalArgumentException("Missing textures property");
            JsonObject texture = HeadCatalog.decodeTexture(payload);
            requireProfileId(expected, HeadCatalog.requireString(texture.get("profileId")));
            return HeadCatalog.skinHash(texture);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid Mojang skin profile", exception);
        }
    }

    private static void requireProfileId(UUID expected, String actual) {
        if (!compact(expected).equalsIgnoreCase(actual)) {
            throw new IllegalArgumentException("Mojang profile UUID did not match the request");
        }
    }

    private static String compact(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    @Override
    public void close() {
        client.shutdownNow();
    }
}
