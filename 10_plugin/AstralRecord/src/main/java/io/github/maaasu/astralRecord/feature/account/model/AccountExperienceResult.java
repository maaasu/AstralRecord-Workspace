package io.github.maaasu.astralRecord.feature.account.model;

import org.jetbrains.annotations.NotNull;

/**
 * 経験値加算後のアカウント進行結果を表します。
 *
 * @param previousAccount 更新前アカウント
 * @param updatedAccount  更新後アカウント
 * @param grantedExperience 今回加算した経験値
 * @param levelUps 今回上昇したレベル数
 */
public record AccountExperienceResult(
        @NotNull AccountModel previousAccount,
        @NotNull AccountModel updatedAccount,
        int grantedExperience,
        int levelUps
) {
    /**
     * 今回レベルアップが発生したかを返します。
     *
     * @return レベルアップした場合は true
     */
    public boolean leveledUp() {
        return levelUps > 0;
    }
}
