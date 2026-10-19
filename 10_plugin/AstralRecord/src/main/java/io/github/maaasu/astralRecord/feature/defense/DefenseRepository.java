package io.github.maaasu.astralRecord.feature.defense;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/** 防衛戦の専用キーを使うHTTP境界です。呼出元が非同期スレッドを選びます。 */
public final class DefenseRepository {
    private final String path;
    private final String key;

    /** @param serverId 実行サーバーID @param key 防衛戦runtime専用キー */
    public DefenseRepository(String serverId, String key) {
        this.path = "/api/defense/runtime/" + URLEncoder.encode(serverId, StandardCharsets.UTF_8);
        this.key = key;
    }

    /** @param instanceId サーバー起動世代 @return 設定と実行指示 @throws IOException 通信または契約不正 */
    public JsonObject poll(String instanceId) throws IOException, InterruptedException {
        var request = ApiRequestUtil.buildRequestBuilder(path + "?instanceId=" + instanceId)
            .header("X-Defense-Runtime-Key", key).GET().build();
        var response = ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString());
        return parse(response);
    }

    /** @param body 最新の受付状態 @throws IOException 受付状態の送信失敗 */
    public void registration(JsonObject body) throws IOException, InterruptedException {
        var request = ApiRequestUtil.buildRequestBuilder(path + "/registration")
            .header("X-Defense-Runtime-Key", key).header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString())).build();
        requireSuccess(ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString()));
    }

    /**
     * @param runId 開催ID
     * @param action reportまたはrewards
     * @param body 冪等操作IDを含む保存済み本文
     * @throws IOException 通信または保存失敗。本文を破棄せず再送します
     */
    public void deliver(String runId, String action, JsonObject body) throws IOException, InterruptedException {
        var request = ApiRequestUtil.buildRequestBuilder(path + "/runs/" + runId + "/" + action)
            .header("X-Defense-Runtime-Key", key).header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString())).build();
        requireSuccess(ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString()));
    }

    private JsonObject parse(HttpResponse<String> response) throws IOException {
        requireSuccess(response);
        if (response.body() == null || response.body().isBlank()) return new JsonObject();
        try { return JsonParser.parseString(response.body()).getAsJsonObject(); }
        catch (RuntimeException ex) { throw new IOException("Invalid defense runtime response", ex); }
    }

    private void requireSuccess(HttpResponse<?> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("Defense runtime HTTP " + response.statusCode());
    }
}
