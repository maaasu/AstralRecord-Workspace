package io.github.maaasu.astralRecord.feature.account.model;

/**
 * プレイヤーレベル設定の結果を表します。
 *
 * @param previousLevel 設定前のプレイヤーレベル
 * @param currentLevel 設定後のプレイヤーレベル
 * @param maxLevel 設定可能なプレイヤーレベルの上限
 * @param updatedAccount 設定後の pending 反映済みアカウント
 */
public record AccountLevelSetResult(
    int previousLevel,
    int currentLevel,
    int maxLevel,
    AccountModel updatedAccount
) {
}
