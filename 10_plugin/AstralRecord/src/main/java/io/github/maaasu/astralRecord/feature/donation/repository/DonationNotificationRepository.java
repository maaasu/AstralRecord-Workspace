package io.github.maaasu.astralRecord.feature.donation.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.donation.model.DonationNotification;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 寄付通知の取得と受信確認を AstralRecord API へ委譲します。 */
public final class DonationNotificationRepository {
    /**
     * 未確認の寄付通知を取得します。呼び出し元は非同期スレッドで実行します。
     *
     * @param userId 対象ユーザー UUID
     * @return 未確認通知の一覧
     * @throws IOException API 応答が不正または通信失敗の場合
     * @throws InterruptedException 通信が中断された場合
     */
    public @NotNull List<DonationNotification> findPending(@NotNull UUID userId)
        throws IOException, InterruptedException {
        String path = "/api/donations/notifications?user_uuid=" + userId;
        HttpRequest request = ApiRequestUtil.buildRequestBuilder(path).GET().build();
        HttpResponse<String> response = ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Donation notifications returned HTTP " + response.statusCode());
        }
        try {
            JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
            List<DonationNotification> notifications = new ArrayList<>();
            for (JsonElement element : array) {
                JsonObject value = element.getAsJsonObject();
                String id = requiredString(value, "id");
                String kind = requiredString(value, "kind");
                UUID.fromString(id);
                long amount = value.get("amount").getAsLong();
                String message = value.has("message") && !value.get("message").isJsonNull()
                    ? value.get("message").getAsString() : "";
                if (message.isBlank()) {
                    throw new IllegalArgumentException("Blank donation notification message");
                }
                if (amount < 0) {
                    throw new IllegalArgumentException("Donation notification amount is negative");
                }
                notifications.add(new DonationNotification(id, kind, amount, message));
            }
            return notifications;
        } catch (RuntimeException e) {
            throw new IOException("Invalid donation notifications response", e);
        }
    }

    /**
     * 表示済み通知を確認済みにします。呼び出し元は非同期スレッドで実行します。
     *
     * @param userId 対象ユーザー UUID
     * @param notificationId 通知 UUID
     * @throws IOException API が確認を受け付けなかった場合
     * @throws InterruptedException 通信が中断された場合
     */
    public void acknowledge(@NotNull UUID userId, @NotNull String notificationId)
        throws IOException, InterruptedException {
        String path = "/api/donations/notifications/" + UUID.fromString(notificationId)
            + "/ack?user_uuid=" + userId;
        HttpRequest request = ApiRequestUtil.buildRequestBuilder(path)
            .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Donation notification acknowledgement returned HTTP " + response.statusCode());
        }
    }

    /**
     * API 応答から必須の非空文字列を読み取ります。
     *
     * @param value 通知の JSON
     * @param key 必須フィールド名
     * @return 非空文字列
     * @throws IllegalArgumentException フィールドが欠ける場合
     */
    private static @NotNull String requiredString(@NotNull JsonObject value, @NotNull String key) {
        if (!value.has(key) || value.get(key).isJsonNull()) {
            throw new IllegalArgumentException("Missing donation notification field: " + key);
        }
        String result = value.get(key).getAsString();
        if (result.isBlank()) {
            throw new IllegalArgumentException("Blank donation notification field: " + key);
        }
        return result;
    }
}
