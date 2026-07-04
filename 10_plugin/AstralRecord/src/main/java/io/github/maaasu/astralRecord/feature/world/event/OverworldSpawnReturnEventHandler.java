package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.ReturnToBaseService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * オーバーワールドのスポーン円内でスニークしたプレイヤーを拠点へ即時帰還させます。
 */
public final class OverworldSpawnReturnEventHandler extends AbstractEventHandler {
    private static final double RETURN_TRIGGER_RADIUS = 2.0D;
    private static final double RETURN_TRIGGER_RADIUS_SQUARED = RETURN_TRIGGER_RADIUS * RETURN_TRIGGER_RADIUS;

    private final WorldService worldService;
    private final ReturnToBaseService returnToBaseService;

    public OverworldSpawnReturnEventHandler(
            @NotNull WorldService worldService,
            @NotNull ReturnToBaseService returnToBaseService
    ) {
        this.worldService = worldService;
        this.returnToBaseService = returnToBaseService;
    }

    /**
     * スニーク押下の瞬間だけスポーン円帰還を判定します。
     *
     * @param event プレイヤースニーク切替イベント
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerToggleSneak(@NotNull PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }

        runSafely(() -> {
            AstPlayer astPlayer = AstPlayerCache.get(event.getPlayer());
            if (astPlayer == null || !isInsideReturnTrigger(event.getPlayer())) {
                return;
            }
            returnToBaseService.beginImmediateReturn(astPlayer);
        }, LogId.E_5756, event.getPlayer().getName(), "sneak");
    }

    private boolean isInsideReturnTrigger(@NotNull Player player) {
        Location triggerCenter = resolveTriggerCenter(player);
        if (triggerCenter == null || triggerCenter.getWorld() == null) {
            return false;
        }
        return horizontalDistanceSquared(player.getLocation(), triggerCenter) <= RETURN_TRIGGER_RADIUS_SQUARED;
    }

    private @Nullable Location resolveTriggerCenter(@NotNull Player player) {
        WorldMasterData worldData = worldService.findByBukkitWorld(player.getWorld());
        if (worldData != null) {
            if (worldData.worldType() != WorldType.OVERWORLD) {
                return null;
            }
            return worldService.resolveSpawnLocation(worldData);
        }

        World world = player.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) {
            return null;
        }
        return world.getSpawnLocation();
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
