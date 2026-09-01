package io.github.maaasu.astralRecord.feature.mail.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.mail.model.MailEntry;
import io.github.maaasu.astralRecord.feature.mail.model.MailFilter;
import io.github.maaasu.astralRecord.feature.mail.model.MailReward;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AstralRecord API の /api/mail と通信する repository です。
 */
public class MailRepository {
    private final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;

    /**
     * アカウントの表示可能メール一覧を取得します。
     *
     * @param accountId 対象アカウント ID
     * @param filter 既読フィルター
     * @return メール一覧
     */
    public @NotNull List<MailEntry> findAvailable(@NotNull UUID accountId, @NotNull MailFilter filter) {
        String path = "/api/mail?account_id=" + accountId + "&filter=" + filter.getApiValue();
        try {
            var client = ApiRequestUtil.buildClient();
            var request = ApiRequestUtil.buildRequestBuilder(path).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return parseList(response.body());
            }
            Logger.log(LogId.E_5190, "find_available", path, "http_status:" + response.statusCode());
            throw new IOException("Unexpected status " + response.statusCode() + " for GET " + path);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.error(LogId.E_5190, e, "find_available", path, failureReason(e));
            throw new RuntimeException(e);
        } catch (IOException e) {
            Logger.error(LogId.E_5190, e, "find_available", path, failureReason(e));
            throw new RuntimeException(e);
        }
    }

    /**
     * メールを既読化します。
     *
     * @param accountId 対象アカウント ID
     * @param mailId メール ID
     * @return 更新後メール。存在しない場合 null
     */
    public @Nullable MailEntry markRead(
        @NotNull UUID accountId,
        @NotNull UUID updatedBy,
        @NotNull String mailId
    ) {
        return sendAction(accountId, updatedBy, mailId, "read");
    }

    /**
     * メールをアカウント単位で削除状態にします。
     *
     * @param accountId 対象アカウント ID
     * @param mailId メール ID
     * @return 削除状態へ更新できた場合 true
     */
    public boolean delete(@NotNull UUID accountId, @NotNull UUID updatedBy, @NotNull String mailId) {
        String encodedMailId = URLEncoder.encode(mailId, StandardCharsets.UTF_8).replace("+", "%20");
        String path = "/api/mail/" + encodedMailId + "/delete";
        try {
            var client = ApiRequestUtil.buildClient();
            var request = ApiRequestUtil.buildRequestBuilder(path)
                .PUT(HttpRequest.BodyPublishers.ofString(actionBody(accountId, updatedBy)))
                .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 204) {
                return true;
            }
            if (response.statusCode() == 404) {
                return false;
            }
            Logger.log(LogId.E_5190, "delete", path, "http_status:" + response.statusCode());
            throw new IOException("Unexpected status " + response.statusCode() + " for PUT " + path);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.error(LogId.E_5190, e, "delete", path, failureReason(e));
            throw new RuntimeException(e);
        } catch (IOException e) {
            Logger.error(LogId.E_5190, e, "delete", path, failureReason(e));
            throw new RuntimeException(e);
        }
    }

    private @Nullable MailEntry sendAction(
        @NotNull UUID accountId,
        @NotNull UUID updatedBy,
        @NotNull String mailId,
        @NotNull String action
    ) {
        String encodedMailId = URLEncoder.encode(mailId, StandardCharsets.UTF_8).replace("+", "%20");
        String path = "/api/mail/" + encodedMailId + "/" + action;
        try {
            var client = ApiRequestUtil.buildClient();
            var request = ApiRequestUtil.buildRequestBuilder(path)
                .PUT(HttpRequest.BodyPublishers.ofString(actionBody(accountId, updatedBy)))
                .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return parse(JsonParser.parseString(response.body()).getAsJsonObject());
            }
            if (response.statusCode() == 404) {
                return null;
            }
            Logger.log(LogId.E_5190, action, path, "http_status:" + response.statusCode());
            throw new IOException("Unexpected status " + response.statusCode() + " for PUT " + path);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.error(LogId.E_5190, e, action, path, failureReason(e));
            throw new RuntimeException(e);
        } catch (IOException e) {
            Logger.error(LogId.E_5190, e, action, path, failureReason(e));
            throw new RuntimeException(e);
        }
    }

    private @NotNull String actionBody(@NotNull UUID accountId, @NotNull UUID updatedBy) {
        JsonObject body = new JsonObject();
        body.addProperty("accountId", accountId.toString());
        body.addProperty("updatedBy", updatedBy.toString());
        return body.toString();
    }

    private @NotNull List<MailEntry> parseList(@NotNull String json) {
        JsonArray array = JsonParser.parseString(json).getAsJsonArray();
        List<MailEntry> mails = new ArrayList<>();
        for (var element : array) {
            if (element.isJsonObject()) {
                mails.add(parse(element.getAsJsonObject()));
            }
        }
        return mails;
    }

    private @NotNull MailEntry parse(@NotNull JsonObject obj) {
        List<MailReward> rewards = new ArrayList<>();
        JsonArray rewardArray = obj.getAsJsonArray("rewards");
        if (rewardArray != null) {
            for (var element : rewardArray) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject rewardObj = element.getAsJsonObject();
                rewards.add(new MailReward(
                    stringValue(rewardObj, "itemId", ""),
                    stringValue(rewardObj, "category", "material"),
                    intValue(rewardObj, "amount", 1)
                ));
            }
        }
        return new MailEntry(
            stringValue(obj, "id", ""),
            stringValue(obj, "icon", "PAPER"),
            stringValue(obj, "title", ""),
            stringValue(obj, "body", ""),
            parseDateTime(stringValue(obj, "publishFrom", LocalDateTime.MIN.toString())),
            parseNullableDateTime(obj, "publishTo"),
            booleanValue(obj, "receiveOnRead", true),
            List.copyOf(rewards),
            booleanValue(obj, "isRead", false),
            parseNullableDateTime(obj, "readAt")
        );
    }

    private @NotNull LocalDateTime parseDateTime(@NotNull String value) {
        return LocalDateTime.parse(value, formatter);
    }

    private @Nullable LocalDateTime parseNullableDateTime(@NotNull JsonObject obj, @NotNull String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        return parseDateTime(obj.get(key).getAsString());
    }

    private @NotNull String stringValue(@NotNull JsonObject obj, @NotNull String key, @NotNull String fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }

    private int intValue(@NotNull JsonObject obj, @NotNull String key, int fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
    }

    private boolean booleanValue(@NotNull JsonObject obj, @NotNull String key, boolean fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsBoolean() : fallback;
    }

    private static @NotNull String failureReason(@NotNull Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
