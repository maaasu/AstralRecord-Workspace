package io.github.maaasu.astralRecord.feature.mob.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.mob.service.NpcPlacementService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.server.ServerLoadEvent;
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

    /**
     * ワールドロード後に対象ワールドの NPC 配置を次 tick でスポーンします。
     *
     * @param event ワールドロードイベント
     */
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

    /**
     * サーバ起動完了後に、ロード済みワールド全体の NPC 配置を再試行します。
     *
     * @param event サーバロード完了イベント
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerLoad(@NotNull ServerLoadEvent event) {
        runSafely(
                () -> plugin.getServer().getScheduler().runTask(
                        plugin,
                        placementService::spawnLoadedWorlds
                ),
                LogId.E_5702,
                "server-load:" + event.getType().name()
        );
    }
}
