package io.github.maaasu.astralarchitect.worldedit;

/**
 * AI候補が安全な差分契約を満たさない場合の例外です。
 */
public final class CandidateValidationException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * 検証失敗を生成します。
     *
     * @param message 失敗理由
     */
    public CandidateValidationException(String message) {
        super(message);
    }
}
