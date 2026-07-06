package io.github.maaasu.astralRecord.feature.loginbonus.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * login-bonus API を通じてログインボーナス受取履歴を読み書きします。
 */
public final class LoginBonusClaimRepository {
    /**
     * 指定月のログインボーナス受取済み日付を取得します。
     *
     * @param accountId 対象アカウントID
     * @param displayMonth 表示対象年月
     * @return 受取済み日付
     */
    public @NotNull Set<LocalDate> loadClaimDates(@NotNull UUID accountId, @NotNull YearMonth displayMonth) {
        LocalDate from = displayMonth.atDay(1);
        LocalDate to = displayMonth.atEndOfMonth();
        String path = "/api/login-bonus/claims?account_id=" + encode(accountId.toString())
            + "&from=" + encode(from.toString())
            + "&to=" + encode(to.toString());
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
                return parseClaimDates(JsonParser.parseString(response.body()).getAsJsonArray());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException | RuntimeException e) {
            Logger.log(LogId.E_5071, e, path);
            throw new RuntimeException(e);
        }
    }

    /**
     * 指定日をログインボーナス受取済みとして登録します。
     *
     * @param accountId 対象アカウントID
     * @param claimDate 受取日
     * @return 新規登録できた場合は true、登録済みまたはアカウント未存在の場合は false
     */
    public boolean tryClaim(@NotNull UUID accountId, @NotNull LocalDate claimDate) {
        String path = "/api/login-bonus/accounts/" + accountId + "/claims";
        JsonObject body = new JsonObject();
        body.addProperty("claimDate", claimDate.toString());
        body.addProperty("updatedBy", accountId.toString());
        try {
            try (var client = ApiRequestUtil.buildClient()) {
                HttpRequest request = ApiRequestUtil.buildRequestBuilder(path)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 404) {
                    return false;
                }
                if (response.statusCode() != 200) {
                    throw new IOException("Unexpected status " + response.statusCode() + " for POST " + path);
                }
                JsonObject responseBody = JsonParser.parseString(response.body()).getAsJsonObject();
                return responseBody.has("wasCreated") && responseBody.get("wasCreated").getAsBoolean();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException | RuntimeException e) {
            Logger.log(LogId.E_5071, e, path);
            throw new RuntimeException(e);
        }
    }

    private @NotNull Set<LocalDate> parseClaimDates(@NotNull JsonArray array) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        for (var element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            if (!obj.has("claimDate") || obj.get("claimDate").isJsonNull()) {
                continue;
            }
            dates.add(LocalDate.parse(obj.get("claimDate").getAsString()));
        }
        return dates;
    }

    private @NotNull String encode(@NotNull String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
