package io.github.maaasu.astralRecord.shared.gui.session;

/** GUI セッションが終了した理由と、終了音の再生可否を表します。 */
public enum GuiSessionEndReason {
    /** プレイヤー操作または遷移失敗により GUI セッションを終了した状態です。 */
    MANUAL_CLOSE(true),

    /** プレイヤーのログアウトまたは接続切断により GUI セッションを終了した状態です。 */
    PLAYER_QUIT(false);

    private final boolean closeSoundEnabled;

    GuiSessionEndReason(boolean closeSoundEnabled) {
        this.closeSoundEnabled = closeSoundEnabled;
    }

    /**
     * セッション終了時に GUI の CLOSE 音を再生するかを返します。
     *
     * @return CLOSE 音を再生する場合は {@code true}
     */
    public boolean isCloseSoundEnabled() {
        return closeSoundEnabled;
    }
}
