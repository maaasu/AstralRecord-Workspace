package io.github.maaasu.astralRecord.shared.interaction;

/**
 * プレイヤー入力を、同じ物理操作として調停する単位に分類します。
 */
public enum InputFamily {
    /** 右クリック系入力です。 */
    RIGHT_CLICK,
    /** 左クリック系入力です。 */
    LEFT_CLICK,
    /** アイテムドロップ入力です。 */
    DROP_ITEM,
    /** ホットバースロット変更入力です。 */
    HOTBAR_SLOT,
    /** スニーク状態変更入力です。 */
    SNEAK,
    /** ブロック設置・破壊系入力です。 */
    BLOCK_MUTATION
}
