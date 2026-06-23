package io.github.maaasu.astralRecord.shared.timing;

import io.github.maaasu.astralRecord.AstralRecord;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

/**
 * 一定時間その場に留まった場合だけ完了する待機処理を提供します。
 */
public final class MovementCancelableWaitService {
    public static final double DEFAULT_MOVE_CANCEL_DISTANCE_BLOCKS = 0.2D;

    private static final double DEFAULT_MOVE_CANCEL_DISTANCE_SQUARED =
        DEFAULT_MOVE_CANCEL_DISTANCE_BLOCKS * DEFAULT_MOVE_CANCEL_DISTANCE_BLOCKS;

    private final AstralRecord plugin;

    /**
     * サービスを初期化します。
     *
     * @param plugin プラグイン本体
     */
    public MovementCancelableWaitService(@NotNull AstralRecord plugin) {
        this.plugin = plugin;
    }

    /**
     * 0.2 ブロックを超える移動でキャンセルされる待機処理を開始します。
     *
     * @param player 待機するプレイヤー
     * @param durationTicks 待機 tick
     * @param callbacks 進行・完了・キャンセル callback
     * @return 待機処理のハンドル
     */
    public @NotNull MovementCancelableWait begin(
        @NotNull Player player,
        long durationTicks,
        @NotNull MovementCancelableWaitCallbacks callbacks
    ) {
        long resolvedDurationTicks = Math.max(1L, durationTicks);
        Location startLocation = player.getLocation().clone();
        MovementCancelableWait wait = new MovementCancelableWait();
        wait.setCallbacks(callbacks);
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            new Runnable() {
                private long elapsedTicks;

                @Override
                public void run() {
                    if (!wait.isActive()) {
                        return;
                    }
                    if (!player.isOnline()) {
                        cancel(wait, MovementCancelableWaitCancelReason.OFFLINE, callbacks);
                        return;
                    }
                    if (hasMoved(player.getLocation(), startLocation)) {
                        cancel(wait, MovementCancelableWaitCancelReason.MOVED, callbacks);
                        return;
                    }

                    elapsedTicks++;
                    callbacks.onTick(
                        elapsedTicks,
                        Math.min(1.0D, (double) elapsedTicks / (double) resolvedDurationTicks)
                    );
                    if (elapsedTicks >= resolvedDurationTicks) {
                        complete(wait, callbacks);
                    }
                }
            },
            1L,
            1L
        );
        wait.setTask(task);
        return wait;
    }

    static boolean cancel(
        @NotNull MovementCancelableWait wait,
        @NotNull MovementCancelableWaitCancelReason reason,
        MovementCancelableWaitCallbacks callbacks
    ) {
        if (!wait.isActive()) {
            return false;
        }
        stop(wait);
        if (callbacks != null) {
            callbacks.onCancel(reason);
        }
        return true;
    }

    private static void complete(
        @NotNull MovementCancelableWait wait,
        @NotNull MovementCancelableWaitCallbacks callbacks
    ) {
        if (!wait.isActive()) {
            return;
        }
        stop(wait);
        callbacks.onComplete();
    }

    private static void stop(@NotNull MovementCancelableWait wait) {
        wait.deactivate();
        if (wait.task() != null) {
            wait.task().cancel();
        }
    }

    private static boolean hasMoved(@NotNull Location current, @NotNull Location start) {
        if (current.getWorld() == null || start.getWorld() == null || !current.getWorld().equals(start.getWorld())) {
            return true;
        }
        return current.distanceSquared(start) > DEFAULT_MOVE_CANCEL_DISTANCE_SQUARED;
    }
}
