package io.github.maaasu.astralRecord.feature.guide.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.guide.model.GuideEntry;
import io.github.maaasu.astralRecord.feature.guide.model.GuideAction;
import io.github.maaasu.astralRecord.feature.guide.model.GuideActionType;
import io.github.maaasu.astralRecord.feature.guide.model.GuideCondition;
import io.github.maaasu.astralRecord.feature.guide.model.GuideConditionType;
import io.github.maaasu.astralRecord.feature.guide.model.GuideStep;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
                    Logger.log(LogId.E_5180, "find_all", path, "http_status:" + response.statusCode());
                    throw new IOException("Unexpected status " + response.statusCode() + " for GET " + path);
                }
                return parseList(response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.error(LogId.E_5180, e, "find_all", path, failureReason(e));
            throw new RuntimeException(e);
        } catch (IOException e) {
            Logger.error(LogId.E_5180, e, "find_all", path, failureReason(e));
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
                        Logger.log(LogId.E_5180, "find_by_id", path, "http_status:" + response.statusCode());
                        throw new IOException("Unexpected status " + response.statusCode() + " for GET " + path);
                    }
                };
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.error(LogId.E_5180, e, "find_by_id", path, failureReason(e));
            throw new RuntimeException(e);
        } catch (IOException e) {
            Logger.error(LogId.E_5180, e, "find_by_id", path, failureReason(e));
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

        int schemaVersion = intValue(obj, "schemaVersion", 0);
        if (schemaVersion != 3) {
            throw new IllegalArgumentException("Unsupported guide schemaVersion: " + schemaVersion + " (id=" + id + ")");
        }
        List<GuideStep> steps = steps(obj);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Guide steps are required: " + id);
        }
        Set<String> stepIds = new HashSet<>();
        for (GuideStep step : steps) {
            if (!stepIds.add(step.id())) {
                throw new IllegalArgumentException("Duplicate guide step id: " + id + ":" + step.id());
            }
        }

        return new GuideEntry(
            schemaVersion,
            id,
            stringValue(obj, "category", "other"),
            intValue(obj, "displayOrder", 0),
            stringValue(obj, "title", id),
            nullableString(obj, "iconMaterial"),
            nullableString(obj, "summary"),
            steps
        );
    }

    private @NotNull List<GuideStep> steps(@NotNull JsonObject obj) {
        JsonElement element = obj.get("steps");
        if (element == null || element.isJsonNull() || !element.isJsonArray()) {
            return List.of();
        }
        List<GuideStep> values = new ArrayList<>();
        for (JsonElement child : element.getAsJsonArray()) {
            if (!child.isJsonObject()) {
                continue;
            }
            JsonObject step = child.getAsJsonObject();
            JsonObject condition = step.has("condition") && step.get("condition").isJsonObject()
                ? step.getAsJsonObject("condition")
                : new JsonObject();
            String stepId = stringValue(step, "id", "").trim();
            String conditionType = stringValue(condition, "type", "").trim();
            if (stepId.isBlank() || conditionType.isBlank()) {
                continue;
            }
            GuideConditionType parsedConditionType = GuideConditionType.parse(conditionType);
            values.add(new GuideStep(
                stepId,
                stringValue(step, "text", stepId),
                strings(step, "details"),
                new GuideCondition(
                    parsedConditionType,
                    nullableString(condition, "targetId"),
                    strings(condition, "targetIds"),
                    parsedConditionType == GuideConditionType.MOB_DEFEATED
                        ? nullableInt(condition, "level")
                        : null
                ),
                action(step)
            ));
        }
        return List.copyOf(values);
    }

    private @Nullable GuideAction action(@NotNull JsonObject step) {
        if (!step.has("action") || step.get("action").isJsonNull() || !step.get("action").isJsonObject()) {
            return null;
        }
        JsonObject action = step.getAsJsonObject("action");
        String typeValue = stringValue(action, "type", "").trim();
        if (typeValue.isBlank()) {
            throw new IllegalArgumentException("Guide action type is required");
        }
        GuideActionType type = GuideActionType.parse(typeValue);
        return new GuideAction(
            type,
            stringValue(action, "description", ""),
            nullableString(action, "npcId"),
            nullableString(action, "menuId")
        );
    }

    private @NotNull List<String> strings(@NotNull JsonObject obj, @NotNull String key) {
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

    private @Nullable Integer nullableInt(@NotNull JsonObject obj, @NotNull String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        JsonElement element = obj.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        int value = element.getAsInt();
        return value < 1 ? null : value;
    }

    private static @NotNull String failureReason(@NotNull Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
