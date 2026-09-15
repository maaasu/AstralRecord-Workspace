package io.github.maaasu.astralRecord.feature.webauth.repository;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.webauth.model.WebLoginChallengeIssueResult;
import io.github.maaasu.astralRecord.feature.webauth.model.WebAuthPlayer;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * AstralRecord API の Web ログインチャレンジ発行エンドポイントへアクセスするリポジトリです。
 */
public class WebAuthRepository {

    /**
     * 登録済みプレイヤーを完全一致の MCID で一意に解決します。
     *
     * @param mcid 検索する Minecraft ID
     * @return 一意に解決できたプレイヤー。未登録または重複時は {@code null}
     * @throws RuntimeException API 通信または応答形式の異常時
     */
    public @Nullable WebAuthPlayer findPlayerByMcid(@NotNull String mcid) {
        String encodedMcid = URLEncoder.encode(mcid.trim(), StandardCharsets.UTF_8);
        try {
            var request = ApiRequestUtil.buildRequestBuilder("/api/web-auth/users/by-mcid/" + encodedMcid)
                .GET()
                .build();
            HttpResponse<String> response = ApiRequestUtil.sharedClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404 || response.statusCode() == 409) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new IOException("Unexpected status " + response.statusCode()
                    + " for GET /api/web-auth/users/by-mcid");
            }

            JsonObject object = JsonParser.parseString(response.body()).getAsJsonObject();
            return new WebAuthPlayer(
                UUID.fromString(object.get("userUuid").getAsString()),
                object.get("mcid").getAsString()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 指定プレイヤーの Web ログインチャレンジを発行します。
     *
     * @param userUuid プレイヤーの user UUID
     * @param mcid プレイヤー MCID
     * @param serverId 発行元サーバー ID
     * @return 発行された Web ログインチャレンジ
     */
    public @NotNull WebLoginChallengeIssueResult createChallenge(
        @NotNull UUID userUuid,
        @NotNull String mcid,
        @NotNull String serverId
    ) {
        JsonObject body = new JsonObject();
        body.addProperty("userUuid", userUuid.toString());
        body.addProperty("mcid", mcid);
        body.addProperty("serverId", serverId);
        body.addProperty("requestedAt", Instant.now().toString());

        try {
            var client = ApiRequestUtil.sharedClient();
                var request = ApiRequestUtil.buildRequestBuilder("/api/web-auth/challenges")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 201 && response.statusCode() != 200) {
                    throw new IOException("Unexpected status " + response.statusCode() + " for POST /api/web-auth/challenges");
                }

                JsonObject object = JsonParser.parseString(response.body()).getAsJsonObject();
                return new WebLoginChallengeIssueResult(
                    UUID.fromString(object.get("challengeId").getAsString()),
                    object.get("loginCode").getAsString(),
                    Instant.parse(object.get("expiresAt").getAsString()),
                    object.get("loginUrl").getAsString()
                );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
