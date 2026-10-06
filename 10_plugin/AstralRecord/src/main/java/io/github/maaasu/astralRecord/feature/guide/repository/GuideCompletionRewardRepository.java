package io.github.maaasu.astralRecord.feature.guide.repository;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.guide.model.GuideCompletionRewardStatus;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

/** 全ガイド達成報酬をアカウント単位で照会・受取します。 */
public final class GuideCompletionRewardRepository {
    /**
     * 受取状態を API から取得します。
     *
     * @param accountId 対象アカウント
     * @return API の受取状態
     */
    public @NotNull GuideCompletionRewardStatus find(@NotNull UUID accountId) {
        String path = path(accountId);
        try {
            HttpRequest request = ApiRequestUtil.buildRequestBuilder(path).GET().build();
            return send(request, path, accountId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("load", accountId, e);
        } catch (IOException | RuntimeException e) {
            throw failure("load", accountId, e);
        }
    }

    /**
     * API 側で完了を再判定して報酬メールを一度だけ発行します。
     *
     * @param accountId 対象アカウント
     * @param updatedBy 更新者 UUID
     * @return API の受取状態
     */
    public @NotNull GuideCompletionRewardStatus claim(@NotNull UUID accountId, @NotNull UUID updatedBy) {
        String path = path(accountId) + "/claim";
        JsonObject body = new JsonObject();
        body.addProperty("updatedBy", updatedBy.toString());
        try {
            HttpRequest request = ApiRequestUtil.buildRequestBuilder(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
            return send(request, path, accountId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("claim", accountId, e);
        } catch (RewardUnavailableException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw failure("claim", accountId, e);
        }
    }

    private @NotNull GuideCompletionRewardStatus send(
        @NotNull HttpRequest request,
        @NotNull String path,
        @NotNull UUID accountId
    ) throws IOException, InterruptedException {
        HttpResponse<String> response = ApiRequestUtil.sharedClient()
            .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 409) {
            throw new RewardUnavailableException();
        }
        if (response.statusCode() != 200) {
            throw new IOException("Unexpected status " + response.statusCode() + " for " + path);
        }
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        UUID returnedAccountId = UUID.fromString(json.get("accountId").getAsString());
        if (!accountId.equals(returnedAccountId)) {
            throw new IOException("Guide completion reward account mismatch");
        }
        return new GuideCompletionRewardStatus(
            returnedAccountId,
            json.get("configured").getAsBoolean(),
            json.get("allCompleted").getAsBoolean(),
            json.get("claimed").getAsBoolean(),
            json.get("canClaim").getAsBoolean(),
            json.has("mailId") && !json.get("mailId").isJsonNull() ? json.get("mailId").getAsString() : null
        );
    }

    private @NotNull RuntimeException failure(@NotNull String operation, @NotNull UUID accountId, @NotNull Exception cause) {
        Logger.error(LogId.E_5183, cause, operation, accountId);
        return cause instanceof RuntimeException runtime ? runtime : new RuntimeException(cause);
    }

    private static @NotNull String path(@NotNull UUID accountId) {
        return "/api/account-guide/" + accountId + "/completion-reward";
    }

    /** 完了条件または報酬設定が満たされていない応答です。 */
    public static final class RewardUnavailableException extends RuntimeException {
    }
}
