package io.github.maaasu.astralRecord.feature.skilltree.model;

/** 構造破損ではなく、参加先の世代・互換性が合わないため状態を保持する。 */
public final class SkillTreeCompatibilityException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    /** 内部診断用の理由を保持する。プレイヤー通知は共通の更新待ち文言を使用する。 */
    public SkillTreeCompatibilityException(String message) { super(message); }
}
