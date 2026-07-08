package io.github.maaasu.astralRecord.feature.guide.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.guide.model.GuideEntry;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * AstralRecord API からガイドマスターを取得する repository です。
 */
public class GuideRepository {

    /**
     * ガイドマスターを全件取得します。
     *
     * @return ガイドマスター一覧
     */
    public @NotNull List<GuideEntry> findAll() {
        String path = "/api/guide";
        try {
            try (var client = ApiRequestUtil.buildClient()) {
                var request = ApiRequestUtil.buildRequestBuilder(path).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    Logger.log(LogId.E_5200, "HTTP " + response.statusCode() + " for GET " + path);
                    throw new IOException("Unexpected status " + response.statusCode() + " for GET " + path);
                }
                return parseList(response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.log(LogId.E_5200, e);
            throw new RuntimeException(e);
        } catch (IOException e) {
            Logger.log(LogId.E_5200, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 指定 ID のガイドマスターを取得します。
     *
     * @param guideId ガイド ID
     * @return ガイドマスター。存在しない場合は null
     */
    public @Nullable GuideEntry findById(@NotNull String guideId) {
        String encoded = URLEncoder.encode(guideId, StandardCharsets.UTF_8).replace("+", "%20");
        String path = "/api/guide/" + encoded;
        try {
            try (var client = ApiRequestUtil.buildClient()) {
                var request = ApiRequestUtil.buildRequestBuilder(path).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                return switch (response.statusCode()) {
                    case 200 -> parse(JsonParser.parseString(response.body()).getAsJsonObject());
                    case 404 -> null;
                    default -> {
                        Logger.log(LogId.E_5200, "HTTP " + response.statusCode() + " for GET " + path);
                        throw new IOException("Unexpected status " + response.statusCode() + " for GET " + path);
                    }
                };
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.log(LogId.E_5200, e);
            throw new RuntimeException(e);
        } catch (IOException e) {
            Logger.log(LogId.E_5200, e);
            throw new RuntimeException(e);
        }
    }

    private @NotNull List<GuideEntry> parseList(@NotNull String json) {
        JsonArray array = JsonParser.parseString(json).getAsJsonArray();
        List<GuideEntry> guides = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                GuideEntry guide = parse(element.getAsJsonObject());
                if (guide != null) {
                    guides.add(guide);
                }
            }
        }
        return guides;
    }

    private @Nullable GuideEntry parse(@NotNull JsonObject obj) {
        String id = stringValue(obj, "id", "");
        if (id.isBlank()) {
            return null;
        }

        return new GuideEntry(
            intValue(obj, "schemaVersion", 1),
            id,
            stringValue(obj, "category", "other"),
            intValue(obj, "displayOrder", 0),
            stringValue(obj, "title", id),
            nullableString(obj, "iconMaterial"),
            nullableString(obj, "summary"),
            stringList(obj, "lines")
        );
    }

    private @NotNull List<String> stringList(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement child : element.getAsJsonArray()) {
            if (child.isJsonPrimitive()) {
                values.add(child.getAsString());
            }
        }
        return List.copyOf(values);
    }

    private @NotNull String stringValue(@NotNull JsonObject obj, @NotNull String key, @NotNull String fallback) {
        String value = nullableString(obj, key);
        return value == null ? fallback : value;
    }

    private @Nullable String nullableString(@NotNull JsonObject obj, @NotNull String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        JsonElement element = obj.get(key);
        return element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private int intValue(@NotNull JsonObject obj, @NotNull String key, int fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        JsonElement element = obj.get(key);
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
            ? element.getAsInt()
            : fallback;
    }
}
