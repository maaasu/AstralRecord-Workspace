package io.github.maaasu.astralRecord.feature.vip.repository;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.vip.model.AccountBenefitsSnapshot;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** アカウント特典API。呼出元は非同期実行と操作IDの再利用を保証します。 */
public final class AccountBenefitsRepository {
    /** 読み取りまたは冪等操作を実行し、HTTP/JSON異常時は例外を返します。 */
    public JsonObject request(UUID accountId, String action, JsonObject body) throws IOException, InterruptedException {
        var builder = ApiRequestUtil.buildRequestBuilder("/api/account/" + accountId + "/benefits" + action);
        HttpRequest request = body == null ? builder.GET().build()
            : builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> response = ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Benefits HTTP " + response.statusCode());
        try { return JsonParser.parseString(response.body()).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("Invalid benefits response", e); }
    }

    /** 操作応答またはGET応答から特典残高を取得します。 */
    public static AccountBenefitsSnapshot snapshot(JsonObject response) {
        JsonObject value = response.has("benefits") ? response.getAsJsonObject("benefits") : response;
        return new AccountBenefitsSnapshot(value.get("instancePriorityUses").getAsLong(),
            instant(value, "donerExpiresAt"), instant(value, "astralderExpiresAt"));
    }

    /** UTCのISO日時を読み取り、未設定値をnullとして返します。 */
    private static Instant instant(JsonObject value, String name) {
        if (!value.has(name) || value.get(name).isJsonNull()) return null;
        String text = value.get(name).getAsString();
        try { return Instant.parse(text); }
        catch (java.time.format.DateTimeParseException ignored) {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        }
    }
}
