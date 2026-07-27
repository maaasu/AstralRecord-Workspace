package io.github.maaasu.astralRecord.feature.condition.task;

import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

/**
 * 期限切れ状態異常を定期的に掃除します。
 */
public final class ConditionCleanupTask {
    private static final long PERIOD_TICKS = 100L;

    private final ConditionService conditionService;
    private BukkitTask task;

    public ConditionCleanupTask(@NotNull ConditionService conditionService) {
        this.conditionService = conditionService;
    }

    public void start(@NotNull JavaPlugin plugin) {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::run, PERIOD_TICKS, PERIOD_TICKS);
    }

    public void stop() {
        if (task == null) {
            return;
        }
        task.cancel();
        task = null;
    }

    public void run() {
        conditionService.purgeExpiredConditions();
    }
}
