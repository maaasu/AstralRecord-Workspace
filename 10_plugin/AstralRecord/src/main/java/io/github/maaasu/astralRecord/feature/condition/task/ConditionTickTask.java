package io.github.maaasu.astralRecord.feature.condition.task;

import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionTickService;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

/**
 * 状態異常の DoT / periodic effect を定期処理します。
 */
public final class ConditionTickTask {
    private static final long PERIOD_TICKS = 5L;
    private static final int MAX_CONDITIONS_PER_RUN = 300;

    private final ConditionService conditionService;
    private final ConditionTickService tickService;
    private BukkitTask task;

    public ConditionTickTask(
            @NotNull ConditionService conditionService,
            @NotNull ConditionTickService tickService
    ) {
        this.conditionService = conditionService;
        this.tickService = tickService;
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
        long nowMs = System.currentTimeMillis();
        int processed = 0;
        for (var condition : conditionService.snapshotAllActiveConditions()) {
            tickService.tickCondition(condition, nowMs);
            processed++;
            if (processed >= MAX_CONDITIONS_PER_RUN) {
                return;
            }
        }
    }
}
