package io.github.maaasu.astralRecord.feature.vip.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

/** Web購入・Webメール受取のオンライン保留をAPIで確定します。 */
public final class OnlinePaidOperationRepository {
    public enum Kind {
        PURCHASE("/api/web/astrald-shop/purchases"),
        MAIL_CLAIM("/api/web/mail/claims");
        private final String path;
        /** 操作種別とAPI pathを結び付けます。 */
        Kind(String path) { this.path = path; }
    }

    /** 本人アカウントの未処理操作を取得します。 */
    public JsonArray pending(Kind kind, UUID accountId) throws IOException, InterruptedException {
        String path = kind.path + "/pending/" + accountId;
        HttpResponse<String> response = send(path, null);
        if (response.statusCode() != 200) throw new IOException("Paid pending HTTP " + response.statusCode());
        try { return JsonParser.parseString(response.body()).getAsJsonArray(); }
        catch (RuntimeException error) { throw new IOException("Invalid paid pending response", error); }
    }

    /** 保存済みbaselineを保持した呼出元から冪等操作IDで確定します。 */
    public JsonObject process(Kind kind, UUID operationId, UUID accountId)
        throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("accountId", accountId.toString());
        body.addProperty("preparedOnline", true);
        HttpResponse<String> response = send(kind.path + "/" + operationId + "/process", body);
        if (response.statusCode() != 200 && response.statusCode() != 409)
            throw new IOException("Paid process HTTP " + response.statusCode());
        try { return JsonParser.parseString(response.body()).getAsJsonObject(); }
        catch (RuntimeException error) { throw new IOException("Invalid paid process response", error); }
    }

    /** 共通API接続設定でGETまたはPOSTします。 */
    private HttpResponse<String> send(String path, JsonObject body) throws IOException, InterruptedException {
        var builder = ApiRequestUtil.buildRequestBuilder(path);
        HttpRequest request = body == null ? builder.GET().build() : builder
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
