package io.github.maaasu.astralRecord.feature.webauth.service;

import io.github.maaasu.astralRecord.feature.webauth.model.WebLoginChallengeIssueResult;
import io.github.maaasu.astralRecord.feature.webauth.model.WebAuthPlayer;
import io.github.maaasu.astralRecord.feature.webauth.repository.WebAuthRepository;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Web ログインチャレンジ発行のアプリケーションサービスです。
 */
public class WebAuthService {
    private final WebAuthRepository repository;

    /**
     * WebAuthService を初期化します。
     *
     * @param repository Web 認証 API リポジトリ
     */
    public WebAuthService(@NotNull WebAuthRepository repository) {
        this.repository = repository;
    }

    /**
     * 指定プレイヤー向けの Web ログインチャレンジを発行します。
     *
     * @param userUuid プレイヤーの user UUID
     * @param mcid プレイヤー MCID
     * @return 発行された Web ログインチャレンジ
     */
    public @NotNull WebLoginChallengeIssueResult issueLoginChallenge(
        @NotNull UUID userUuid,
        @NotNull String mcid
    ) {
        return repository.createChallenge(userUuid, mcid, ConfigProperties.getInstance().getApiServerId());
    }

    /**
     * MCID で一意に解決できる登録済みプレイヤー向けに Web ログインチャレンジを発行します。
     *
     * @param mcid 対象プレイヤーの Minecraft ID
     * @return 発行された Web ログインチャレンジ。未登録または MCID 重複時は {@code null}
     * @throws RuntimeException API 通信またはチャレンジ発行に失敗した場合
     */
    public @Nullable WebLoginChallengeIssueResult issueLoginChallengeForMcid(@NotNull String mcid) {
        WebAuthPlayer player = repository.findPlayerByMcid(mcid);
        if (player == null) {
            return null;
        }
        return issueLoginChallenge(player.userUuid(), player.mcid());
    }
}
