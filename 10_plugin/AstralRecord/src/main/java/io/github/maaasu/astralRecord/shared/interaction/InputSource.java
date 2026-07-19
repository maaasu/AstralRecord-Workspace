package io.github.maaasu.astralRecord.shared.interaction;

/**
 * 調停対象の入力を観測した入口を表します。
 * Bukkit のイベント型へ依存せず、gateway で対応する値へ変換して使用します。
 */
public enum InputSource {
    /** 汎用のプレイヤー操作イベント由来です。 */
    PLAYER_INTERACT,
    /** エンティティ操作イベント由来です。 */
    PLAYER_INTERACT_ENTITY,
    /** エンティティ上の位置を伴う操作イベント由来です。 */
    PLAYER_INTERACT_AT_ENTITY,
    /** 腕振りアニメーション由来です。 */
    PLAYER_ARM_SWING,
    /** エンティティ攻撃の事前判定イベント由来です。 */
    PRE_PLAYER_ATTACK_ENTITY,
    /** アイテムドロップイベント由来です。 */
    PLAYER_DROP_ITEM,
    /** ホットバースロット変更イベント由来です。 */
    PLAYER_ITEM_HELD,
    /** スニーク状態変更イベント由来です。 */
    PLAYER_TOGGLE_SNEAK,
    /** ブロック破壊イベント由来です。 */
    BLOCK_BREAK,
    /** ブロック設置イベント由来です。 */
    BLOCK_PLACE,
    /** テストまたはgateway内で合成された入力です。 */
    SYNTHETIC
}
