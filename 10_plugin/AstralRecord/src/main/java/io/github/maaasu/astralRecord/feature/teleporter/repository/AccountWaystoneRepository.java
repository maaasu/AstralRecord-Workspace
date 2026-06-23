package io.github.maaasu.astralRecord.feature.teleporter.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * account-waystone API を通じてアカウント別解除状態を読み書きします。
 */
public final class AccountWaystoneRepository {
    /**
     * アカウント別の解除済みウェイストーン ID を取得します。
     *
     * @param accountId 対象アカウント ID
     * @return 解除済みウェイストーン ID
     */
    @NotNull
    public Set<String> loadUnlockedWaystoneIds(@NotNull UUID accountId) {
        String path = "/api/account-waystone/" + accountId;
        try {
            try (var client = ApiRequestUtil.buildClient()) {
                HttpRequest request = ApiRequestUtil.buildRequestBuilder(path).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 404) {
                    return Set.of();
                }
                if (response.statusCode() != 200) {
                    throw new IOException("Unexpected status " + response.statusCode() + " for GET " + path);
                }
                return parseUnlockedIds(JsonParser.parseString(response.body()).getAsJsonObject());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException | RuntimeException e) {
            Logger.log(LogId.E_5954, e, path);
            throw new RuntimeException(e);
        }
    }

    /**
     * 指定ウェイストーンを解除済みとして登録します。
     *
     * @param accountId 対象アカウント ID
     * @param waystoneId 解除するウェイストーン ID
     */
    public void unlock(@NotNull UUID accountId, @NotNull String waystoneId) {
        String path = "/api/account-waystone/" + accountId + "/unlock";
        JsonObject body = new JsonObject();
        body.addProperty("waystoneId", waystoneId);
        body.addProperty("updatedBy", accountId.toString());
        try {
            try (var client = ApiRequestUtil.buildClient()) {
                HttpRequest request = ApiRequestUtil.buildRequestBuilder(path)
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("Unexpected status " + response.statusCode() + " for POST " + path);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException | RuntimeException e) {
            Logger.log(LogId.E_5954, e, path);
            throw new RuntimeException(e);
        }
    }

    @NotNull
    private Set<String> parseUnlockedIds(@NotNull JsonObject obj) {
        Set<String> ids = new LinkedHashSet<>();
        if (!obj.has("unlockedWaystoneIds") || !obj.get("unlockedWaystoneIds").isJsonArray()) {
            return ids;
        }
        JsonArray array = obj.getAsJsonArray("unlockedWaystoneIds");
        for (var element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            String id = element.getAsString().trim();
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }
}
