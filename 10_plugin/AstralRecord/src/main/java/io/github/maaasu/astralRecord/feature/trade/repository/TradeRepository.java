package io.github.maaasu.astralRecord.feature.trade.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.trade.model.TradeCommitItem;
import io.github.maaasu.astralRecord.feature.trade.model.TradeCommitRequest;
import io.github.maaasu.astralRecord.feature.trade.model.TradeCommitResult;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** AstralRecord API のトレード原子確定エンドポイントを呼び出します。 */
public final class TradeRepository {
    private static final String COMMIT_PATH = "/api/trade/commit";

    /**
     * item・個体所有権・Gold を API transaction で確定します。
     *
     * @param request 確定要求
     * @return API が確定した結果
     * @throws TradeCommitRejectedException API が明示的に 4xx の業務拒否を返した場合
     * @throws IllegalStateException 送信後の通信・応答解析、または 5xx により確定結果を判別できない場合
     */
    public @NotNull TradeCommitResult commit(@NotNull TradeCommitRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("operationId", request.operationId().toString());
        body.addProperty("playerAAccountId", request.playerAAccountId().toString());
        body.addProperty("playerBAccountId", request.playerBAccountId().toString());
        body.add("playerAItems", items(request.playerAItems()));
        body.add("playerBItems", items(request.playerBItems()));
        body.addProperty("playerAGold", request.playerAGold());
        body.addProperty("playerBGold", request.playerBGold());
        body.addProperty("updatedBy", request.updatedBy().toString());

        HttpResponse<String> response = send(ApiRequestUtil.buildRequestBuilder(COMMIT_PATH)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build());
        if (response.statusCode() >= 400 && response.statusCode() < 500) {
            throw new TradeCommitRejectedException(response.statusCode(), response.body());
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                "POST " + COMMIT_PATH + " returned HTTP " + response.statusCode() + ": " + response.body()
            );
        }
        JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
        return new TradeCommitResult(
            UUID.fromString(result.get("operationId").getAsString()),
            uuidList(result, "playerAAffectedInventoryEntryIds"),
            uuidList(result, "playerBAffectedInventoryEntryIds"),
            Instant.parse(result.get("completedAt").getAsString())
        );
    }

    private @NotNull JsonArray items(@NotNull List<TradeCommitItem> items) {
        JsonArray array = new JsonArray();
        for (TradeCommitItem item : items) {
            JsonObject object = new JsonObject();
            object.addProperty("sourceInventoryEntryId", item.sourceInventoryEntryId().toString());
            object.addProperty("quantity", item.quantity());
            array.add(object);
        }
        return array;
    }

    private @NotNull HttpResponse<String> send(@NotNull HttpRequest request) {
        try {
            return ApiRequestUtil.buildClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Trade API request was interrupted.", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to request " + COMMIT_PATH, e);
        }
    }

    private @NotNull List<UUID> uuidList(@NotNull JsonObject object, @NotNull String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            return List.of();
        }
        List<UUID> result = new ArrayList<>();
        for (var element : object.getAsJsonArray(key)) {
            result.add(UUID.fromString(element.getAsString()));
        }
        return List.copyOf(result);
    }

    /** Trade API が要求を受理せず、DB transaction を確定していないと明示した 4xx 応答です。 */
    public static final class TradeCommitRejectedException extends IllegalStateException {
        private final int statusCode;

        /**
         * 業務拒否の status と Problem Details を保持します。
         *
         * @param statusCode API の HTTP status code
         * @param responseBody API が返した Problem Details 本文
         */
        public TradeCommitRejectedException(int statusCode, @NotNull String responseBody) {
            super("POST " + COMMIT_PATH + " returned HTTP " + statusCode + ": " + responseBody);
            this.statusCode = statusCode;
        }

        /**
         * API が明示した業務拒否の HTTP status code を返します。
         *
         * @return 4xx status code
         */
        public int getStatusCode() {
            return statusCode;
        }
    }
}
