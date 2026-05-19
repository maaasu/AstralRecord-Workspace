package io.github.maaasu.astralRecord.feature.player.save;

/**
 * プレイヤーデータ保存が発火した契機を表します。
 */
public enum PlayerSaveTrigger {
    /** ログアウト時の保存。 */
    LOGOUT,
    /** コマンドなどによる手動保存。 */
    MANUAL,
    /** 定期実行による自動保存。 */
    AUTO,
    /** プラグイン停止時の保存。 */
    PLUGIN_DISABLE,
}
