package io.github.maaasu.astralRecord.feature.fishing.repository;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.fishing.model.FishingMasters.Area;
import io.github.maaasu.astralRecord.feature.fishing.model.FishingMasters.FishDefinition;
import io.github.maaasu.astralRecord.feature.fishing.model.FishingMasters.Rules;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.List;

/** 釣りマスタをAPI経由で取得します。非同期ロードからだけ呼び出します。 */
public final class FishingRepository {
    private final Gson gson = new Gson();

    /** @return 全魚定義。HTTP失敗・不正なJSON/定義では例外を送出します */
    public List<FishDefinition> findFish() { return fetch("/api/fishing/fish", FishDefinition.class); }

    /** @return 全釣り場定義。HTTP失敗・不正なJSON/定義では例外を送出します */
    public List<Area> findAreas() { return fetch("/api/fishing/areas", Area.class); }

    /** @return 全操作ルール。HTTP失敗・不正なJSON/定義では例外を送出します */
    public List<Rules> findRules() { return fetch("/api/fishing/rules", Rules.class); }

    private <T> List<T> fetch(String path, Class<T> type) {
        try {
            var response = ApiRequestUtil.sharedClient().send(
                ApiRequestUtil.buildRequestBuilder(path).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("Fishing API status " + response.statusCode() + ": " + path);
            var array = JsonParser.parseString(response.body()).getAsJsonArray();
            var result = new java.util.ArrayList<T>();
            for (var element : array) result.add(gson.fromJson(element, type));
            return List.copyOf(result);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fishing master load interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Fishing master load failed: " + path, exception);
        }
    }
}
