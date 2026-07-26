package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.shared.teleport.PlayerTeleportService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * 発動スキルの短距離移動を、移動可否・全身の経路遮蔽・足場・頭上空間を確認して適用します。
 */
public final class SkillMovementService {

    private static final double SEARCH_STEP = 0.25D;
    private static final double[] BODY_HORIZONTAL_OFFSETS = {-0.3D, 0.0D, 0.3D};
    private static final double[] BODY_HEIGHT_OFFSETS = {0.1D, 0.9D, 1.7D};
    private final ConditionService conditionService;

    /** 状態異常の移動可否を確認するサービスで初期化します。 */
    public SkillMovementService(@NotNull ConditionService conditionService) {
        this.conditionService = conditionService;
    }

    /**
     * 移動前後の座標です。
     *
     * @param start 移動開始地点
     * @param end 実際の移動先
     * @param moved テレポートが成功し、距離が変化したか
     */
    public record MovementResult(
            @NotNull Location start,
            @NotNull Location end,
            boolean moved
    ) {
    }

    /** 視線方向へ安全に前進します。 */
    public @NotNull MovementResult dash(
            @NotNull Player player,
            @NotNull AstEntity mover,
            double maxDistance
    ) {
        return move(player, mover, facingDirection(player), maxDistance);
    }

    /** 視線と反対方向へ安全に後退します。 */
    public @NotNull MovementResult backstep(
            @NotNull Player player,
            @NotNull AstEntity mover,
            double maxDistance
    ) {
        return move(player, mover, facingDirection(player).multiply(-1.0D), maxDistance);
    }

    /** 視線方向へ安全に瞬間移動します。 */
    public @NotNull MovementResult blink(
            @NotNull Player player,
            @NotNull AstEntity mover,
            double maxDistance
    ) {
        return move(player, mover, facingDirection(player), maxDistance);
    }

    private @NotNull MovementResult move(
            @NotNull Player player,
            @NotNull AstEntity mover,
            @NotNull Vector direction,
            double maxDistance
    ) {
        Location start = player.getLocation().clone();
        if (!conditionService.canMove(mover)) {
            return new MovementResult(start, start.clone(), false);
        }
        Location destination = findDestination(start, direction, Math.max(0.0D, maxDistance));
        if (destination.distanceSquared(start) <= 1.0E-6D) {
            return new MovementResult(start, start.clone(), false);
        }
        boolean moved = PlayerTeleportService.teleport(
                player,
                destination,
                PlayerTeleportEvent.TeleportCause.PLUGIN
        );
        return new MovementResult(start, moved ? player.getLocation().clone() : start.clone(), moved);
    }

    @NotNull Location findDestination(
            @NotNull Location start,
            @NotNull Vector direction,
            double maxDistance
    ) {
        World world = start.getWorld();
        if (world == null || maxDistance <= 0.0D) {
            return start.clone();
        }
        Vector normalized = horizontal(direction, start.getYaw());
        double pathLimit = clearPathDistance(start, normalized, maxDistance);
        for (double distance = pathLimit; distance >= SEARCH_STEP; distance -= SEARCH_STEP) {
            Location horizontalTarget = start.clone().add(normalized.clone().multiply(distance));
            for (int yOffset : new int[]{0, 1, -1}) {
                Location candidate = horizontalTarget.clone().add(0.0D, yOffset, 0.0D);
                if (isSafe(candidate)) {
                    return candidate;
                }
            }
        }
        return start.clone();
    }

    private boolean isSafe(@NotNull Location location) {
        World world = location.getWorld();
        if (world == null || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return false;
        }
        Block floor = world.getBlockAt(location.getBlockX(), location.getBlockY() - 1, location.getBlockZ());
        return !floor.isPassable() && isBodyClear(location);
    }

    private double clearPathDistance(
            @NotNull Location start,
            @NotNull Vector direction,
            double maxDistance
    ) {
        double furthestClear = 0.0D;
        for (double distance = Math.min(SEARCH_STEP, maxDistance);
             distance <= maxDistance + 1.0E-8D;
             distance = Math.min(maxDistance, distance + SEARCH_STEP)) {
            Location sample = start.clone().add(direction.clone().multiply(distance));
            if (!isBodyClear(sample)) {
                break;
            }
            furthestClear = distance;
            if (distance >= maxDistance) {
                break;
            }
        }
        return furthestClear;
    }

    private boolean isBodyClear(@NotNull Location location) {
        World world = location.getWorld();
        if (world == null || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return false;
        }
        for (double xOffset : BODY_HORIZONTAL_OFFSETS) {
            for (double zOffset : BODY_HORIZONTAL_OFFSETS) {
                for (double yOffset : BODY_HEIGHT_OFFSETS) {
                    Location occupiedLocation = location.clone().add(xOffset, yOffset, zOffset);
                    Block occupied = world.getBlockAt(
                            occupiedLocation.getBlockX(),
                            occupiedLocation.getBlockY(),
                            occupiedLocation.getBlockZ()
                    );
                    if (!occupied.isPassable()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static @NotNull Vector facingDirection(@NotNull Player player) {
        return horizontal(player.getEyeLocation().getDirection(), player.getLocation().getYaw());
    }

    static @NotNull Vector horizontal(@NotNull Vector direction, float yaw) {
        Vector horizontal = direction.clone().setY(0.0D);
        if (horizontal.lengthSquared() > 1.0E-8D) {
            return horizontal.normalize();
        }
        double yawRadians = Math.toRadians(yaw);
        return new Vector(-Math.sin(yawRadians), 0.0D, Math.cos(yawRadians));
    }
}
