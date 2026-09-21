package io.github.maaasu.astralRecord.feature.history.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.history.service.PlayerActivityHistoryService;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** プレイ本体と独立した管理用活動履歴 API を呼び出します。 */
public final class PlayerActivityHistoryRepository {
    private static final String BATCH_PATH = "/api/history/activity/batch";

    /** バッチを送信します。成功応答以外は呼び出し元で再送する例外として返します。 */
    public void submit(@NotNull PlayerActivityHistoryService.ActivityBatch batch) {
        JsonObject body = batch.toJson();
        HttpResponse<String> response;
        try {
            response = ApiRequestUtil.sharedClient().send(
                ApiRequestUtil.buildRequestBuilder(BATCH_PATH)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Player activity history request was interrupted.", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to request " + BATCH_PATH, exception);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                "POST " + BATCH_PATH + " returned HTTP " + response.statusCode() + ": " + response.body()
            );
        }
    }
}
