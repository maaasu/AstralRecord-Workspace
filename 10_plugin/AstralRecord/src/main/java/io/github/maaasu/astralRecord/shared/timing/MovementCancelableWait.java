package io.github.maaasu.astralRecord.shared.timing;

import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 実行中の移動キャンセル付き待機処理を表すハンドルです。
 */
public final class MovementCancelableWait {
    private BukkitTask task;
    private MovementCancelableWaitCallbacks callbacks;
    private boolean active = true;

    MovementCancelableWait() {
    }

    /**
     * 待機処理をキャンセルします。
     *
     * @param reason キャンセル理由
     * @return 実行中の待機をキャンセルできた場合は {@code true}
     */
    public boolean cancel(@NotNull MovementCancelableWaitCancelReason reason) {
        return MovementCancelableWaitService.cancel(this, reason, callbacks);
    }

    boolean isActive() {
        return active;
    }

    void deactivate() {
        active = false;
    }

    @Nullable BukkitTask task() {
        return task;
    }

    void setTask(@NotNull BukkitTask task) {
        this.task = task;
    }

    void setCallbacks(@NotNull MovementCancelableWaitCallbacks callbacks) {
        this.callbacks = callbacks;
    }
}
