package io.github.maaasu.astralarchitect.worldedit;

/**
 * WorldEdit選択または基準ブロックがチケット要件を満たさない場合の例外です。
 */
public final class SelectionException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * 例外を生成します。
     *
     * @param message プレイヤーへ説明可能な理由
     */
    public SelectionException(String message) {
        super(message);
    }

    /**
     * 原因付き例外を生成します。
     *
     * @param message プレイヤーへ説明可能な理由
     * @param cause 原因
     */
    public SelectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
