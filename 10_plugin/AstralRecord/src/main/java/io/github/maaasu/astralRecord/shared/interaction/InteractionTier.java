package io.github.maaasu.astralRecord.shared.interaction;

/**
 * 入力候補の大分類優先度です。
 * 数値が大きいtierほど先に選択されます。
 */
public enum InteractionTier {
    /** ロード中など、入力全体を占有する状態です。 */
    INPUT_LOCK(500),
    /** スキルツリーなど、現在の操作文脈を占有する状態です。 */
    EXCLUSIVE_CONTEXT(400),
    /** NPC、装置、バニラ操作対象などのワールド上インタラクトです。 */
    WORLD_INTERACTION(300),
    /** bundleやpotionなどの手持ちアイテム使用です。 */
    ITEM_USE(200),
    /** アクションリングなど、他候補がない場合の処理です。 */
    FALLBACK(100);

    private final int priority;

    InteractionTier(int priority) {
        this.priority = priority;
    }

    /**
     * tier比較用の優先度を返します。
     *
     * @return 数値が大きいほど高い優先度
     */
    public int priority() {
        return priority;
    }
}
