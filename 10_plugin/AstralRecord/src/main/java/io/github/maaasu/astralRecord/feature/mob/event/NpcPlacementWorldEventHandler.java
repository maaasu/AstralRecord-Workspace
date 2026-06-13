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
 * ワールドロード時に管理 YAML 上の NPC をスポーンするイベントハンドラです。
 */
public final class NpcPlacementWorldEventHandler extends AbstractEventHandler {

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldLoad(@NotNull WorldLoadEvent event) {
        runSafely(
                () -> plugin.getServer().getScheduler().runTask(
                        plugin,
                        () -> placementService.spawnForWorld(event.getWorld())
                ),
                LogId.E_5702,
                event.getWorld().getName()
        );
    }
}
