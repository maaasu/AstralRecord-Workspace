package io.github.maaasu.astralRecord.feature.loginbonus.repository;

/**
 * ログインボーナス受取登録の結果です。
 */
public enum LoginBonusClaimResult {
    /** 新規に受取登録されました。 */
    CREATED,
    /** 対象日はすでに受け取り済みです。 */
    ALREADY_CLAIMED,
    /** 受取登録に失敗しました。 */
    FAILED
}
