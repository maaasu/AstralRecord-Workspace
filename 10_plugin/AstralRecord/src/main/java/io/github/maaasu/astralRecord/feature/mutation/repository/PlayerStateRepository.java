package io.github.maaasu.astralRecord.feature.mutation.repository;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryApiException;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateAcknowledgementException;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateOutcomeUnknownException;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

/** プレイヤー完成状態の保存と受領確認を行うAPI境界です。ゲーム状態は変更しません。 */
public class PlayerStateRepository {
    private static final int MAX_POST_ATTEMPTS = 2;

    /**
     * 同じsnapshotIdと内容で再送可能な完成状態を保存します。
     * @param payload 不変のスナップショットJSON
     * @return snapshotIdと保存済み版情報だけを含むACK
     * @throws RuntimeException 通信またはAPI検証に失敗した場合
     */
    public JsonObject saveSnapshot(String payload) {
        String path = "/api/player-state/snapshots";
        JsonObject snapshot = JsonParser.parseString(payload).getAsJsonObject();
        UUID snapshotId = UUID.fromString(snapshot.get("snapshotId").getAsString());
        UUID accountId = UUID.fromString(snapshot.get("accountId").getAsString());
        RuntimeException unresolvedFailure = null;
        for (int attempt = 1; attempt <= MAX_POST_ATTEMPTS; attempt++) {
            try {
                var request = ApiRequestUtil.buildRequestBuilder(path)
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                var response = ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return parseAcknowledgement(response.body());
                }

                InventoryApiException failure = new InventoryApiException(
                    "POST", path, response.statusCode(), response.body());
                if (!isOutcomeUnknown(response.statusCode()) && unresolvedFailure == null) throw failure;
                JsonObject recovered = findCompleted(snapshotId, accountId, failure);
                if (recovered != null) return recovered;
                unresolvedFailure = failure;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new PlayerStateOutcomeUnknownException(interrupted);
            } catch (IOException failure) {
                JsonObject recovered = findCompleted(snapshotId, accountId, failure);
                if (recovered != null) return recovered;
                unresolvedFailure = new UncheckedIOException(failure);
            }
        }
        throw new PlayerStateOutcomeUnknownException(unresolvedFailure);
    }

    /**
     * HTTP 200 の ACK を検証できなかった場合に、API が保存した固定 ACK を照会します。
     *
     * @param snapshotId 照会する不変 snapshot ID
     * @param accountId 所有アカウント ID
     * @return 完了済み snapshot の ACK。台帳に存在しない場合は {@code null}
     * @throws RuntimeException 通信または結果応答の検証に失敗した場合
     */
    public JsonObject findCompletedSnapshot(UUID snapshotId, UUID accountId) {
        String path = "/api/player-state/snapshots/" + snapshotId + "?accountId=" + accountId;
        try {
            var request = ApiRequestUtil.buildRequestBuilder(path).GET().build();
            var response = ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) return null;
            if (response.statusCode() != 200) {
                throw new InventoryApiException("GET", path, response.statusCode(), response.body());
            }
            JsonObject status = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!status.has("status") || !"COMPLETED".equals(status.get("status").getAsString())
                || !status.has("ack") || !status.get("ack").isJsonObject()) {
                throw new PlayerStateAcknowledgementException(
                    new IllegalStateException("Invalid player snapshot status response"));
            }
            return status.getAsJsonObject("ack");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Player snapshot lookup interrupted", interrupted);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (InventoryApiException failure) {
            throw failure;
        } catch (PlayerStateAcknowledgementException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new PlayerStateAcknowledgementException(invalid);
        }
    }

    private static JsonObject parseAcknowledgement(String body) {
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new PlayerStateAcknowledgementException(invalid);
        }
    }

    private JsonObject findCompleted(UUID snapshotId, UUID accountId, Throwable originalFailure) {
        try {
            return findCompletedSnapshot(snapshotId, accountId);
        } catch (PlayerStateAcknowledgementException invalidAcknowledgement) {
            throw invalidAcknowledgement;
        } catch (RuntimeException lookupFailure) {
            originalFailure.addSuppressed(lookupFailure);
            return null;
        }
    }

    private static boolean isOutcomeUnknown(int statusCode) {
        return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500;
    }
}
