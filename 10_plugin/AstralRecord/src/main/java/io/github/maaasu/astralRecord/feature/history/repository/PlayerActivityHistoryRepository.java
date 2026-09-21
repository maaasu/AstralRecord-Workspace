package io.github.maaasu.astralRecord.feature.history.repository;

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

    /**
     * バッチを送信します。
     *
     * @throws HistorySubmissionException HTTP 応答が成功でない場合。呼び出し側は retryable を確認します
     * @throws IllegalStateException 通信または中断により結果を取得できない場合
     */
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
            throw new HistorySubmissionException(response.statusCode());
        }
    }

    /** 活動履歴 API の HTTP 拒否と、同一バッチを再送すべきかを表します。 */
    public static final class HistorySubmissionException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final int statusCode;

        private HistorySubmissionException(int statusCode) {
            super("POST " + BATCH_PATH + " returned HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        /** @return API が返した HTTP status code */
        public int statusCode() {
            return statusCode;
        }

        /** @return タイムアウト・レート制限・サーバー障害として同一 batchId で再送する場合 true */
        public boolean retryable() {
            return statusCode == 408 || statusCode == 429 || statusCode >= 500;
        }
    }
}
