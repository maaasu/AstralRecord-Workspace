package io.github.maaasu.astralRecord.feature.webauth.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Web ログインチャレンジ発行結果を表すモデルです。
 *
 * @param challengeId API 側で採番されたチャレンジ ID
 * @param loginCode プレイヤーへ表示する一回限りのログインコード
 * @param expiresAt ログインコードの有効期限
 * @param loginUrl Web ログイン画面 URL
 */
public record WebLoginChallengeIssueResult(
    @NotNull UUID challengeId,
    @NotNull String loginCode,
    @NotNull Instant expiresAt,
    @NotNull String loginUrl
) {
}
