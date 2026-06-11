package io.github.maaasu.astralRecord.feature.webauth.service;

import io.github.maaasu.astralRecord.feature.webauth.model.WebLoginChallengeIssueResult;
import io.github.maaasu.astralRecord.feature.webauth.repository.WebAuthRepository;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import org.jetbrains.annotations.NotNull;

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
}
