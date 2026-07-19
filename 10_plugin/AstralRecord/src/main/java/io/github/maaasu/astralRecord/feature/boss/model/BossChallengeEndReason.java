package io.github.maaasu.astralRecord.feature.boss.model;

/**
 * Reason why a boss challenge ended.
 */
public enum BossChallengeEndReason {
    DEFEATED(true, "討伐完了"),
    TIME_LIMIT(false, "制限時間超過"),
    DEATH_LIMIT(false, "全滅回数上限"),
    NO_PARTICIPANTS(false, "参加者不在"),
    PARTICIPANT_REQUIREMENT_NOT_MET(false, "参加条件未達"),
    FIELD_PREPARE_FAILED(false, "フィールド準備失敗"),
    TRANSFER_FAILED(false, "転送失敗"),
    BOSS_SPAWN_FAILED(false, "ボス出現失敗"),
    ADMIN_STOP(false, "管理者による停止"),
    PLUGIN_SHUTDOWN(false, "プラグイン停止");

    private final boolean success;
    private final String displayName;

    BossChallengeEndReason(boolean success, String displayName) {
        this.success = success;
        this.displayName = displayName;
    }

    /**
     * Returns whether this reason means a successful clear.
     *
     * @return true when defeated
     */
    public boolean success() {
        return success;
    }

    /**
     * プレイヤー向けの終了理由表示を返します。
     *
     * @return 日本語の終了理由
     */
    public String displayName() {
        return displayName;
    }
}
