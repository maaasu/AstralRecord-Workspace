package io.github.maaasu.astralRecord.feature.mutation.model;

/** HTTP成功でも受領確認の契約を満たさない応答を表します。再抽選・再計算は行いません。 */
public final class PlayerStateAcknowledgementException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    /**
     * @param cause 応答構造または内容の不整合
     */
    public PlayerStateAcknowledgementException(Throwable cause) {
        super("Invalid player-state acknowledgement", cause);
    }
}
