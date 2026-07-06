package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.OverworldTeleportService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 拠点ワールドのスポーン地点付近でスニークしたプレイヤーにオーバーワールド転送 GUI を開きます。
 */
public final class BaseWorldSpawnTeleportEventHandler extends AbstractEventHandler {
    private static final double TELEPORT_TRIGGER_RADIUS = 2.0D;
    private static final double TELEPORT_TRIGGER_RADIUS_SQUARED = TELEPORT_TRIGGER_RADIUS * TELEPORT_TRIGGER_RADIUS;

    private final WorldService worldService;
    private final OverworldTeleportService teleportService;
    private final OverworldTeleportGuiEventHandler guiEventHandler;

    /**
     * イベントハンドラーを生成します。
     *
     * @param worldService ワールド情報の解決に使うサービス
     * @param teleportService 拠点ワールド判定に使うサービス
     * @param guiEventHandler オーバーワールド転送 GUI の表示処理
     */
    public BaseWorldSpawnTeleportEventHandler(
            @NotNull WorldService worldService,
            @NotNull OverworldTeleportService teleportService,
            @NotNull OverworldTeleportGuiEventHandler guiEventHandler
    ) {
        this.worldService = worldService;
        this.teleportService = teleportService;
        this.guiEventHandler = guiEventHandler;
    }

    /**
     * スニーク押下の瞬間だけ拠点スポーン円内かを判定し、転送 GUI を開きます。
     *
     * @param event プレイヤースニーク切替イベント
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerToggleSneak(@NotNull PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }

        runSafely(() -> {
            Player player = event.getPlayer();
            if (guiEventHandler.isOpen(player)
                    || !teleportService.isBaseWorld(player.getWorld())
                    || !isInsideTeleportTrigger(player)) {
                return;
            }
            guiEventHandler.open(player);
        }, LogId.E_5755, event.getPlayer().getName(), "base-spawn-sneak");
    }

    private boolean isInsideTeleportTrigger(@NotNull Player player) {
        Location triggerCenter = resolveTriggerCenter(player);
        if (triggerCenter == null || triggerCenter.getWorld() == null) {
            return false;
        }
        return horizontalDistanceSquared(player.getLocation(), triggerCenter) <= TELEPORT_TRIGGER_RADIUS_SQUARED;
    }

    private @Nullable Location resolveTriggerCenter(@NotNull Player player) {
        WorldMasterData worldData = worldService.findByBukkitWorld(player.getWorld());
        if (worldData == null || worldData.worldType() != WorldType.BASE) {
            return null;
        }
        return worldService.resolveSpawnLocation(worldData);
    }

    private static double horizontalDistanceSquared(@NotNull Location from, @NotNull Location to) {
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().getUID().equals(to.getWorld().getUID())) {
            return Double.POSITIVE_INFINITY;
        }

        double deltaX = from.getX() - to.getX();
        double deltaZ = from.getZ() - to.getZ();
        return (deltaX * deltaX) + (deltaZ * deltaZ);
    }
}
