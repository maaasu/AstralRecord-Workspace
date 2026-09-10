package io.github.maaasu.astralRecord.feature.mutation.model;

/** 通信結果からSQLの確定・取消を判定できない保存を表し、同一snapshotの回復を要求します。 */
public final class PlayerStateOutcomeUnknownException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    /**
     * @param cause 結果照会を含めて解消できなかった通信例外
     */
    public PlayerStateOutcomeUnknownException(Throwable cause) {
        super("Player-state snapshot outcome is unresolved", cause);
    }
}
