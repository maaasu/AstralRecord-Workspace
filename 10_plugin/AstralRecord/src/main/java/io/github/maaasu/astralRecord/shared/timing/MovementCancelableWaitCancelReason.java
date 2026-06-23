package io.github.maaasu.astralRecord.shared.timing;

/**
 * 移動キャンセル付き待機処理の終了理由です。
 */
public enum MovementCancelableWaitCancelReason {
    MOVED,
    HELD_ITEM_CHANGED,
    OFFLINE,
    MANUAL
}
