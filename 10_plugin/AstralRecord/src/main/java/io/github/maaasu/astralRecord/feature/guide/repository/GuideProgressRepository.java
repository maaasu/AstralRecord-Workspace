package io.github.maaasu.astralRecord.feature.guide.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.guide.model.GuideStepKey;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * AstralRecord API 経由でアカウント単位のガイド進行を読み書きする repository です。
 */
public final class GuideProgressRepository {

    /**
     * アカウントの完了済みガイド手順を取得します。
     *
     * @param accountId アカウント ID
     * @return 完了済み手順キー
     */
    public @NotNull Set<GuideStepKey> findByAccountId(@NotNull UUID accountId) {
        String path = "/api/account-guide/" + accountId;
        try {
            try (var client = ApiRequestUtil.buildClient()) {
                var request = ApiRequestUtil.buildRequestBuilder(path).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("Unexpected status " + response.statusCode() + " for GET " + path);
                }
                return parseProgress(response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.error(LogId.E_5182, e, "load", accountId, failureReason(e));
            throw new RuntimeException(e);
        } catch (IOException e) {
            Logger.error(LogId.E_5182, e, "load", accountId, failureReason(e));
            throw new RuntimeException(e);
        }
    }

    /**
     * ガイド手順を完了済みとして冪等登録します。
     *
     * @param accountId アカウント ID
     * @param key 完了するガイド手順
     * @param updatedBy 更新者 user ID
     */
    public void completeStep(
        @NotNull UUID accountId,
        @NotNull GuideStepKey key,
        @NotNull UUID updatedBy
    ) {
        String path = "/api/account-guide/" + accountId + "/steps/complete";
        JsonObject json = new JsonObject();
        json.addProperty("guideId", key.guideId());
        json.addProperty("stepId", key.stepId());
        json.addProperty("updatedBy", updatedBy.toString());
        String body = json.toString();
        try {
            try (var client = ApiRequestUtil.buildClient()) {
                var request = ApiRequestUtil.buildRequestBuilder(path)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("Unexpected status " + response.statusCode() + " for POST " + path);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.error(LogId.E_5182, e, "complete", accountId + ":" + key.guideId() + ":" + key.stepId(), failureReason(e));
            throw new RuntimeException(e);
        } catch (IOException e) {
            Logger.error(LogId.E_5182, e, "complete", accountId + ":" + key.guideId() + ":" + key.stepId(), failureReason(e));
            throw new RuntimeException(e);
        }
    }

    private @NotNull Set<GuideStepKey> parseProgress(@NotNull String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray completedSteps = root.has("completedSteps") && root.get("completedSteps").isJsonArray()
            ? root.getAsJsonArray("completedSteps")
            : new JsonArray();
        Set<GuideStepKey> result = new LinkedHashSet<>();
        for (JsonElement element : completedSteps) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject step = element.getAsJsonObject();
            String guideId = stringValue(step, "guideId");
            String stepId = stringValue(step, "stepId");
            if (!guideId.isBlank() && !stepId.isBlank()) {
                result.add(new GuideStepKey(guideId, stepId));
            }
        }
        return Set.copyOf(result);
    }

    private @NotNull String stringValue(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement element = obj.get(key);
        return element == null || element.isJsonNull() || !element.isJsonPrimitive()
            ? ""
            : element.getAsString().trim();
    }

    private static @NotNull String failureReason(@NotNull Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
