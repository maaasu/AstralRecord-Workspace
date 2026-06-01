package io.github.maaasu.astralRecord.feature.adventurerecord.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.adventurerecord.model.AdventureMobRecord;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AstralRecord API を通じて冒険記録を読み書きするリポジトリです。
 */
public class AdventureRecordRepository {

    /**
     * Mob 討伐記録一覧を取得します。
     *
     * @param accountId 対象アカウント ID
     * @param category 絞り込みカテゴリ。null の場合は全カテゴリ
     * @return 討伐記録一覧
     */
    public @NotNull List<AdventureMobRecord> findMobRecords(
        @NotNull UUID accountId,
        @Nullable MobCategory category
    ) {
        String path = "/api/adventure-record/mob?account_id=" + accountId;
        if (category != null) {
            path += "&category=" + URLEncoder.encode(category.name(), StandardCharsets.UTF_8);
        }

        try {
            try (var client = ApiRequestUtil.buildClient()) {
                var request = ApiRequestUtil.buildRequestBuilder(path).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("Unexpected status " + response.statusCode() + " for GET " + path);
                }
                JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
                List<AdventureMobRecord> result = new ArrayList<>();
                for (JsonElement element : array) {
                    if (element.isJsonObject()) {
                        result.add(parseRecord(element.getAsJsonObject()));
                    }
                }
                return result;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Mob 討伐を 1 件記録します。
     *
     * @param accountId 対象アカウント ID
     * @param mobId Mob マスタ ID
     * @param category Mob カテゴリ
     * @param updatedBy 更新者ユーザー ID
     */
    public void recordMobDefeat(
        @NotNull UUID accountId,
        @NotNull String mobId,
        @NotNull MobCategory category,
        @NotNull UUID updatedBy
    ) {
        JsonObject body = new JsonObject();
        body.addProperty("accountId", accountId.toString());
        body.addProperty("mobId", mobId);
        body.addProperty("mobCategory", category.name());
        body.addProperty("updatedBy", updatedBy.toString());

        try {
            try (var client = ApiRequestUtil.buildClient()) {
                var request = ApiRequestUtil.buildRequestBuilder("/api/adventure-record/mob/defeat")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("Unexpected status " + response.statusCode() + " for POST /api/adventure-record/mob/defeat");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private @NotNull AdventureMobRecord parseRecord(@NotNull JsonObject obj) {
        return new AdventureMobRecord(
            UUID.fromString(obj.get("accountMobRecordId").getAsString()),
            UUID.fromString(obj.get("accountId").getAsString()),
            obj.get("mobId").getAsString(),
            MobCategory.from(obj.get("mobCategory").getAsString()),
            obj.get("defeatCount").getAsLong(),
            Instant.parse(obj.get("firstDefeatedAt").getAsString()),
            Instant.parse(obj.get("lastDefeatedAt").getAsString())
        );
    }
}
