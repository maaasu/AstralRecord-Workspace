package io.github.maaasu.astralRecord.feature.serverrecord.repository;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.serverrecord.model.ServerRecordSnapshot;
import io.github.maaasu.astralRecord.feature.serverrecord.model.ServerRecordType;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 共有HTTPクライアントでランキングを一括取得します。 */
public final class ServerRecordRepository {
    /**
     * メインスレッドを待機させず全種別を取得します。
     * @param limit 各種別の上限人数
     * @return 検証済み応答。通信・JSON・契約不正時は例外完了
     */
    public CompletableFuture<ServerRecordSnapshot> fetchAsync(int limit) {
        var request = ApiRequestUtil.buildRequestBuilder("/api/server-records?limit=" + limit).GET().build();
        return ApiRequestUtil.sharedClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Server record HTTP status: " + response.statusCode());
                    }
                    return parse(response.body(), limit);
                });
    }

    /** @param json 応答JSON @param limit 上限人数 @return 完全な不変スナップショット */
    private ServerRecordSnapshot parse(String json, int limit) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        var rankings = new EnumMap<ServerRecordType, List<ServerRecordSnapshot.Entry>>(ServerRecordType.class);
        for (JsonElement element : root.getAsJsonArray("rankings")) {
            JsonObject ranking = element.getAsJsonObject();
            ServerRecordType type = ServerRecordType.parse(ranking.get("type").getAsString());
            if (type == null) continue;
            if (rankings.containsKey(type) || !ranking.get("available").getAsBoolean()) {
                throw new IllegalStateException("Invalid server record type: " + type.id());
            }
            var entries = new ArrayList<ServerRecordSnapshot.Entry>();
            for (JsonElement entryElement : ranking.getAsJsonArray("entries")) {
                JsonObject entry = entryElement.getAsJsonObject();
                int rank = entry.get("rank").getAsBigDecimal().intValueExact();
                long value = entry.get("value").getAsBigDecimal().longValueExact();
                if (rank != entries.size() + 1 || value < 0 || entries.size() >= limit) {
                    throw new IllegalStateException("Invalid server record entry");
                }
                entries.add(new ServerRecordSnapshot.Entry(rank, entry.get("playerName").getAsString(), value));
            }
            rankings.put(type, List.copyOf(entries));
        }
        if (rankings.size() != ServerRecordType.values().length) {
            throw new IllegalStateException("Incomplete server record response");
        }
        return new ServerRecordSnapshot(Instant.parse(root.get("generatedAt").getAsString()), rankings);
    }
}
