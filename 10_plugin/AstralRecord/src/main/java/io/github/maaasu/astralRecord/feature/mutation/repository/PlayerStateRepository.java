package io.github.maaasu.astralRecord.feature.mutation.repository;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryApiException;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** プレイヤー完成状態の保存と受領確認を行うAPI境界です。ゲーム状態は変更しません。 */
public class PlayerStateRepository {
    /**
     * 同じsnapshotIdと内容で再送可能な完成状態を保存します。
     * @param payload 不変のスナップショットJSON
     * @return snapshotIdと保存済み版情報だけを含むACK
     * @throws RuntimeException 通信またはAPI検証に失敗した場合
     */
    public JsonObject saveSnapshot(String payload) {
        String path = "/api/player-state/snapshots";
        try (var client = ApiRequestUtil.buildClient()) {
            var request = ApiRequestUtil.buildRequestBuilder(path)
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new InventoryApiException("POST", path, response.statusCode(), response.body());
            }
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Player snapshot save interrupted", interrupted);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }
}
