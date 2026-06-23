package io.github.maaasu.astralRecord.shared.timing;

import org.jetbrains.annotations.NotNull;

/**
 * 移動キャンセル付き待機処理の進行・完了・キャンセル通知を受け取ります。
 */
public interface MovementCancelableWaitCallbacks {

    /**
     * 待機処理の tick ごとに呼び出されます。
     *
     * @param elapsedTicks 経過 tick
     * @param progress 0.0 から 1.0 までの進捗
     */
    default void onTick(long elapsedTicks, double progress) {
    }

    /**
     * 待機時間が満了したときに呼び出されます。
     */
    void onComplete();

    /**
     * 待機処理がキャンセルされたときに呼び出されます。
     *
     * @param reason キャンセル理由
     */
    default void onCancel(@NotNull MovementCancelableWaitCancelReason reason) {
    }
}
