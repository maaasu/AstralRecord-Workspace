package io.github.maaasu.astralRecord.feature.user.model;

/**
 * ログイン前ユーザー処理の結果。
 */
public enum UserPreLoginResult {
    /** 接続を許可します。 */
    ALLOWED,
    /** 有効な BAN により接続を拒否します。 */
    BANNED,
    /** ユーザーまたは初期アカウントの準備に失敗したため接続を拒否します。 */
    INITIALIZATION_FAILED
}
