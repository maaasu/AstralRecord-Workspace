package io.github.maaasu.astralRecord.feature.textdisplay.event;

import io.github.maaasu.astralRecord.core.event.EventHandler;
import io.github.maaasu.astralRecord.feature.textdisplay.service.TextDisplayPlacementService;
import org.bukkit.event.EventPriority;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * ワールドロード後に遅延付きで固定 TextDisplay の表示を再試行するイベントハンドラです。
 */
public final class TextDisplayPlacementWorldEventHandler implements EventHandler {

    private static final long WORLD_LOAD_SPAWN_DELAY_TICKS = 20L;
    private static final int WORLD_LOAD_SPAWN_MAX_ATTEMPTS = 5;

    private final Plugin plugin;
    private final TextDisplayPlacementService placementService;

    /**
     * ハンドラを初期化します。
     *
     * @param plugin           プラグイン本体
     * @param placementService 固定 TextDisplay 配置サービス
     */
    public TextDisplayPlacementWorldEventHandler(
            @NotNull Plugin plugin,
            @NotNull TextDisplayPlacementService placementService
    ) {
        this.plugin = plugin;
        this.placementService = placementService;
    }

    /**
     * ワールドロード後に固定 TextDisplay の表示を遅延再試行します。
     *
     * @param event ワールドロードイベント
     */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldLoad(@NotNull WorldLoadEvent event) {
        scheduleSpawnRetry(event.getWorld().getName(), WORLD_LOAD_SPAWN_MAX_ATTEMPTS);
    }

    private void scheduleSpawnRetry(@NotNull String worldName, int remainingAttempts) {
        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> attemptSpawnRetry(worldName, remainingAttempts),
                WORLD_LOAD_SPAWN_DELAY_TICKS
        );
    }

    private void attemptSpawnRetry(@NotNull String worldName, int remainingAttempts) {
        var world = plugin.getServer().getWorld(worldName);
        if (world != null) {
            placementService.spawnForWorld(world);
        }
        if (remainingAttempts <= 1 || !placementService.hasPendingPlacements()) {
            return;
        }
        scheduleSpawnRetry(worldName, remainingAttempts - 1);
    }
}
