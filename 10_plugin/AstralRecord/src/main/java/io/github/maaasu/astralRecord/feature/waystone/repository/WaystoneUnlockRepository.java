package io.github.maaasu.astralRecord.feature.waystone.repository;

import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * AstralRecord API の account-waystone API と通信する repository です。
 */
public final class WaystoneUnlockRepository {

    /**
     * アカウントが開放済みのウェイストーンID一覧を取得します。
     *
     * @param accountId アカウントID
     * @return 開放済みウェイストーンID
     * @throws IOException API通信に失敗した場合
     */
    public @NotNull Set<String> findUnlockedIds(@NotNull UUID accountId) throws IOException {
        String path = "/api/account-waystone/" + accountId;
        try (var client = ApiRequestUtil.buildClient()) {
            var request = ApiRequestUtil.buildRequestBuilder(path).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Set.of();
            }
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " for GET " + path);
            }
            Set<String> ids = new HashSet<>();
            var array = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("unlockedWaystoneIds");
            for (var element : array) {
                ids.add(element.getAsString());
            }
            return ids;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while GET " + path, e);
        }
    }

    /**
     * 指定ウェイストーンを開放済みとして登録します。
     *
     * @param accountId アカウントID
     * @param waystoneId ウェイストーンID
     * @param updatedBy 更新者ユーザーID
     * @throws IOException API通信に失敗した場合
     */
    public void unlock(@NotNull UUID accountId, @NotNull String waystoneId, @NotNull UUID updatedBy) throws IOException {
        String path = "/api/account-waystone/" + accountId + "/unlock";
        String body = ApiRequestUtil.buildJsonBody(json -> {
            json.addProperty("waystoneId", waystoneId);
            json.addProperty("updatedBy", updatedBy.toString());
            return Unit.INSTANCE;
        });
        try (var client = ApiRequestUtil.buildClient()) {
            HttpRequest request = ApiRequestUtil.buildRequestBuilder(path)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 204) {
                throw new IOException("HTTP " + response.statusCode() + " for POST " + path);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while POST " + path, e);
        }
    }
}
