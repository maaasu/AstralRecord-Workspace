package io.github.maaasu.astralRecord.feature.condition.task;

import io.github.maaasu.astralRecord.feature.condition.model.ActiveCondition;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionTickService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 状態異常の DoT / periodic effect を定期処理します。
 */
public final class ConditionTickTask {
    private static final long PERIOD_TICKS = 5L;
    private static final int MAX_CONDITIONS_PER_RUN = 300;

    private final ConditionService conditionService;
    private final ConditionTickService tickService;
    private BukkitTask task;
    private int nextConditionIndex;

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
        nextConditionIndex = 0;
    }

    public void run() {
        long nowMs = System.currentTimeMillis();
        List<ActiveCondition> conditions = conditionService.snapshotAllActiveConditions();
        if (conditions.isEmpty()) {
            nextConditionIndex = 0;
            return;
        }

        int startIndex = Math.floorMod(nextConditionIndex, conditions.size());
        int processCount = Math.min(MAX_CONDITIONS_PER_RUN, conditions.size());
        for (int offset = 0; offset < processCount; offset++) {
            ActiveCondition condition = conditions.get((startIndex + offset) % conditions.size());
            try {
                tickService.tickCondition(condition, nowMs);
            } catch (RuntimeException ex) {
                Logger.error(LogId.E_5901, ex, condition.conditionId(), condition.targetId());
            }
        }
        nextConditionIndex = (startIndex + processCount) % conditions.size();
    }
}
