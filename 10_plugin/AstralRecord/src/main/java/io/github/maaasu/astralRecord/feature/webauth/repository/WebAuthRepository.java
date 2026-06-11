package io.github.maaasu.astralRecord.feature.webauth.repository;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.webauth.model.WebLoginChallengeIssueResult;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

/**
 * AstralRecord API の Web ログインチャレンジ発行エンドポイントへアクセスするリポジトリです。
 */
public class WebAuthRepository {

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
            try (var client = ApiRequestUtil.buildClient()) {
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
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
