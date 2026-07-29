package io.github.maaasu.astralarchitect.worldedit;

/**
 * 適用対象ワールドがチケット作成時または適用時の状態から変化している場合の例外です。
 */
public final class WorldConflictException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * 競合を生成します。
     *
     * @param message 競合内容
     */
    public WorldConflictException(String message) {
        super(message);
    }
}
