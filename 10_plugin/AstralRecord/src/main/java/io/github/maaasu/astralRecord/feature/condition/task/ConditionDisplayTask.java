package io.github.maaasu.astralRecord.feature.condition.task;

import io.github.maaasu.astralRecord.feature.condition.display.ConditionDisplayService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

/**
 * 状態異常の継続表示を定期更新します。
 */
public final class ConditionDisplayTask {
    private static final long PERIOD_TICKS = 10L;

    private final ConditionService conditionService;
    private final ConditionDisplayService displayService;
    private BukkitTask task;

    public ConditionDisplayTask(
            @NotNull ConditionService conditionService,
            @NotNull ConditionDisplayService displayService
    ) {
        this.conditionService = conditionService;
        this.displayService = displayService;
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
        for (var target : conditionService.snapshotVisibleTargets()) {
            displayService.refreshTargetDisplay(target, conditionService.getActiveConditions(target));
        }
    }
}
