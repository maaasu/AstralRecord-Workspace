package io.github.maaasu.astralRecord.feature.mob.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.mob.service.NpcPlacementService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * ワールドロード後に遅延付きで NPC 配置スポーンを再試行するイベントハンドラです。
 */
public final class NpcPlacementWorldEventHandler extends AbstractEventHandler {

    private static final long WORLD_LOAD_SPAWN_DELAY_TICKS = 20L;
    private static final int WORLD_LOAD_SPAWN_MAX_ATTEMPTS = 5;

    private final Plugin plugin;
    private final NpcPlacementService placementService;

    /**
     * ハンドラを初期化します。
     *
     * @param plugin           プラグイン本体
     * @param placementService NPC 配置サービス
     */
    public NpcPlacementWorldEventHandler(
            @NotNull Plugin plugin,
            @NotNull NpcPlacementService placementService
    ) {
        this.plugin = plugin;
        this.placementService = placementService;
    }

    /**
     * ワールドロード後に `/mob npc reload` と同じスポーン処理を遅延実行し、
     * 未スポーン配置が残る間だけ再試行します。
     *
     * @param event ワールドロードイベント
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldLoad(@NotNull WorldLoadEvent event) {
        String worldName = event.getWorld().getName();
        runSafely(
                () -> scheduleSpawnRetry(WORLD_LOAD_SPAWN_MAX_ATTEMPTS, worldName),
                LogId.E_5706,
                "npc_world_load",
                worldName
        );
    }

    private void scheduleSpawnRetry(int remainingAttempts, @NotNull String worldName) {
        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> runSafely(
                        () -> attemptSpawnRetry(remainingAttempts, worldName),
                        LogId.E_5706,
                        "npc_world_retry",
                        worldName + ":remaining=" + remainingAttempts
                ),
                WORLD_LOAD_SPAWN_DELAY_TICKS
        );
    }

    private void attemptSpawnRetry(int remainingAttempts, @NotNull String worldName) {
        placementService.spawnLoadedWorlds();
        if (remainingAttempts <= 1 || !placementService.hasPendingPlacements()) {
            return;
        }
        scheduleSpawnRetry(remainingAttempts - 1, worldName);
    }
}
