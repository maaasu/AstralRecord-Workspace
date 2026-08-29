package io.github.maaasu.astralRecord.feature.skill.active.service;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/**
 * 発動スキルの遅延・反復処理を発動者単位で追跡し、安全に破棄します。
 */
public final class SkillTaskService {

    private final Plugin plugin;
    private final Map<UUID, Map<String, TrackedTask>> tasksByCaster = new ConcurrentHashMap<>();

    /** Bukkit scheduler を利用するプラグインで初期化します。 */
    public SkillTaskService(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    /** 指定 tick 後に1回だけ処理を実行します。 */
    public void later(@NotNull UUID casterId, @NotNull String scope, long delayTicks, @NotNull Runnable action) {
        replace(casterId, scope, plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            remove(casterId, scope);
            action.run();
        }, Math.max(0L, delayTicks)));
    }

    /**
     * 指定回数の反復処理を実行します。index は 0 から始まります。
     */
    public void repeat(
            @NotNull UUID casterId,
            @NotNull String scope,
            long delayTicks,
            long periodTicks,
            int executions,
            @NotNull IntConsumer action
    ) {
        repeat(casterId, scope, delayTicks, periodTicks, executions, action, () -> { });
    }

    /**
     * 指定回数の反復処理を実行し、完了・中断時にcleanupを1回だけ実行します。
     *
     * @param casterId 発動者UUID
     * @param scope 発動単位の識別子
     * @param delayTicks 初回までのtick数
     * @param periodTicks 実行間隔
     * @param executions 実行回数
     * @param action tickごとの処理
     * @param cleanup 正常完了・例外・lifecycle中断時の後始末
     */
    public void repeat(
            @NotNull UUID casterId,
            @NotNull String scope,
            long delayTicks,
            long periodTicks,
            int executions,
            @NotNull IntConsumer action,
            @NotNull Runnable cleanup
    ) {
        int safeExecutions = Math.max(1, executions);
        int[] index = {0};
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            try {
                action.accept(index[0]++);
            } catch (RuntimeException exception) {
                cancel(casterId, scope);
                throw exception;
            }
            if (index[0] >= safeExecutions) {
                cancel(casterId, scope);
            }
        }, Math.max(0L, delayTicks), Math.max(1L, periodTicks));
        replace(casterId, scope, task, cleanup);
    }

    /** 同一発動者・scope の処理を停止します。 */
    public void cancel(@NotNull UUID casterId, @NotNull String scope) {
        Map<String, TrackedTask> tasks = tasksByCaster.get(casterId);
        if (tasks == null) {
            return;
        }
        TrackedTask task = tasks.remove(scope);
        if (task != null) {
            task.cancel();
        }
        if (tasks.isEmpty()) {
            tasksByCaster.remove(casterId, tasks);
        }
    }

    /** 発動者に紐づく全処理を停止します。 */
    public void clearCaster(@NotNull UUID casterId) {
        Map<String, TrackedTask> tasks = tasksByCaster.remove(casterId);
        if (tasks != null) {
            tasks.values().forEach(TrackedTask::cancel);
        }
    }

    /** 全発動スキル処理を停止します。 */
    public void stop() {
        tasksByCaster.values().forEach(tasks -> tasks.values().forEach(TrackedTask::cancel));
        tasksByCaster.clear();
    }

    private void replace(@NotNull UUID casterId, @NotNull String scope, @NotNull BukkitTask task) {
        replace(casterId, scope, task, () -> { });
    }

    private void replace(
            @NotNull UUID casterId,
            @NotNull String scope,
            @NotNull BukkitTask task,
            @NotNull Runnable cleanup
    ) {
        Map<String, TrackedTask> tasks = tasksByCaster.computeIfAbsent(
                casterId, ignored -> new ConcurrentHashMap<>()
        );
        TrackedTask previous = tasks.put(scope, new TrackedTask(task, cleanup));
        if (previous != null) {
            previous.cancel();
        }
    }

    private void remove(@NotNull UUID casterId, @NotNull String scope) {
        Map<String, TrackedTask> tasks = tasksByCaster.get(casterId);
        if (tasks == null) {
            return;
        }
        tasks.remove(scope);
        if (tasks.isEmpty()) {
            tasksByCaster.remove(casterId, tasks);
        }
    }

    private static final class TrackedTask {
        private final BukkitTask task;
        private final Runnable cleanup;
        private boolean cancelled;

        private TrackedTask(@NotNull BukkitTask task, @NotNull Runnable cleanup) {
            this.task = task;
            this.cleanup = cleanup;
        }

        private synchronized void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            task.cancel();
            cleanup.run();
        }
    }
}
