package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.shared.teleport.PlayerTeleportService;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    /**
     * 視線と反対方向へ水平 velocity を設定します。
     * 移動禁止状態では velocity を設定せず、適用した場合は設定値を返します。
     *
     * @param player          velocity を設定するプレイヤー
     * @param mover           移動可否を確認する主体
     * @param velocityStrength 後退 velocity の水平強度
     * @return 設定した velocity。移動禁止または強度が不正なら {@code null}
     */
    public @Nullable Vector backstepVelocity(
            @NotNull Player player,
            @NotNull AstEntity mover,
            double velocityStrength
    ) {
        if (!conditionService.canMove(mover) || !(velocityStrength > 0.0D)) {
            return null;
        }
        Vector velocity = facingDirection(player).multiply(-velocityStrength);
        player.setVelocity(velocity);
        return velocity;
    }

    /**
     * 指定した velocity を移動可能なプレイヤーへ設定します。
     * 移動禁止状態または有限値でない velocity の場合はプレイヤーを変更しません。
     *
     * @param player velocityを設定するプレイヤー
     * @param mover 移動可否を確認する主体
     * @param velocity 設定する三軸velocity
     * @return 設定したvelocityの複製、適用できない場合は {@code null}
     */
    public @Nullable Vector velocity(
            @NotNull Player player,
            @NotNull AstEntity mover,
            @NotNull Vector velocity
    ) {
        if (!conditionService.canMove(mover) || !isFinite(velocity)) {
            return null;
        }
        Vector applied = velocity.clone();
        player.setVelocity(applied);
        return applied;
    }

    /** 視線方向へ安全に瞬間移動します。 */
    public @NotNull MovementResult blink(
            @NotNull Player player,
            @NotNull AstEntity mover,
            double maxDistance
    ) {
        return move(player, mover, facingDirection(player), maxDistance);
    }

    /**
     * 視点方向へ最大距離まで進み、全身が直線的に到達できる位置へ瞬間移動します。
     * 上向きの垂直移動は空中を許可し、真下へは移動せず、それ以外は地面の上に足を置きます。
     *
     * @param player 移動するプレイヤー
     * @param mover 移動可否を確認する主体
     * @param maxDistance 探索する最大距離
     * @return テレポート前後の座標と移動結果
     */
    public @NotNull MovementResult blinkGrounded(
            @NotNull Player player,
            @NotNull AstEntity mover,
            double maxDistance
    ) {
        Location start = player.getLocation().clone();
        if (!conditionService.canMove(mover)) {
            return new MovementResult(start, start.clone(), false);
        }
        Vector direction = player.getEyeLocation().getDirection();
        if (!isFinite(direction) || direction.lengthSquared() <= 1.0E-8D) {
            return new MovementResult(start, start.clone(), false);
        }
        direction.normalize();
        if (direction.getY() < -0.9D) {
            return new MovementResult(start, start.clone(), false);
        }
        Location destination = findGroundedDestination(
                player,
                start,
                direction,
                Math.min(10.0D, maxDistance)
        );
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

    private @NotNull Location findGroundedDestination(
            @NotNull Player player,
            @NotNull Location start,
            @NotNull Vector direction,
            double maxDistance
    ) {
        World world = start.getWorld();
        if (world == null || maxDistance <= 0.0D) {
            return start.clone();
        }
        double pathLimit = loadedPathDistance(player, start, direction, maxDistance);
        if (pathLimit < SEARCH_STEP) {
            return start.clone();
        }
        double bodyCollisionDistance = firstBodyCollisionDistance(
                player,
                start,
                direction,
                pathLimit
        );
        if (Double.isFinite(bodyCollisionDistance)) {
            pathLimit = Math.min(pathLimit, Math.max(0.0D, bodyCollisionDistance - 0.05D));
        }
        if (direction.getY() > 0.99D) {
            Location destination = start.clone().add(direction.clone().multiply(pathLimit));
            return isBlinkBodyClear(player, destination) ? destination : start.clone();
        }
        Location eye = player.getEyeLocation();
        RayTraceResult sightHit = world.rayTraceBlocks(
                eye,
                direction,
                pathLimit,
                FluidCollisionMode.NEVER,
                true
        );
        if (sightHit != null) {
            double hitDistance = eye.toVector().distance(sightHit.getHitPosition());
            pathLimit = Math.min(pathLimit, Math.max(0.0D, hitDistance - 0.05D));
        }
        for (double distance = pathLimit; distance >= SEARCH_STEP; distance -= SEARCH_STEP) {
            Location target = start.clone().add(direction.clone().multiply(distance));
            for (int yOffset : new int[]{0, 1, -1}) {
                Location candidate = target.clone().add(0.0D, yOffset, 0.0D);
                if (!world.isChunkLoaded(candidate.getBlockX() >> 4, candidate.getBlockZ() >> 4)) {
                    continue;
                }
                Block floor = world.getBlockAt(
                        candidate.getBlockX(),
                        candidate.getBlockY() - 1,
                        candidate.getBlockZ()
                );
                if (floor.isPassable()) {
                    continue;
                }
                Double surfaceY = floorSurfaceY(floor, candidate.getX(), candidate.getZ());
                if (surfaceY == null) {
                    continue;
                }
                candidate.setY(surfaceY);
                if (isBlinkBodyClear(player, candidate)
                        && isBlinkPathClear(player, start, candidate)) {
                    return candidate;
                }
            }
        }
        return start.clone();
    }

    /** 足元の真下にある衝突面の最上面をワールド座標で返します。 */
    private @Nullable Double floorSurfaceY(@NotNull Block floor, double x, double z) {
        double localX = x - floor.getX();
        double localZ = z - floor.getZ();
        Double highest = null;
        for (BoundingBox shapeBox : floor.getCollisionShape().getBoundingBoxes()) {
            if (localX < shapeBox.getMinX() || localX > shapeBox.getMaxX()
                    || localZ < shapeBox.getMinZ() || localZ > shapeBox.getMaxZ()) {
                continue;
            }
            double surface = floor.getY() + shapeBox.getMaxY();
            if (highest == null || surface > highest) {
                highest = surface;
            }
        }
        return highest;
    }

    /** 開始地点から着地点まで、全身が直線的に通過できる場合だけ許可します。 */
    private boolean isBlinkPathClear(
            @NotNull Player player,
            @NotNull Location start,
            @NotNull Location destination
    ) {
        Vector displacement = destination.toVector().subtract(start.toVector());
        double distance = displacement.length();
        if (!Double.isFinite(distance) || distance <= 1.0E-6D) {
            return false;
        }
        Vector direction = displacement.multiply(1.0D / distance);
        return loadedPathDistance(player, start, direction, distance) >= distance - 1.0E-6D
                && firstBodyCollisionDistance(player, start, direction, distance) >= distance - 1.0E-6D;
    }

    private double loadedPathDistance(
            @NotNull Player player,
            @NotNull Location start,
            @NotNull Vector direction,
            double maxDistance
    ) {
        double furthestLoaded = 0.0D;
        for (double distance = Math.min(SEARCH_STEP, maxDistance);
             distance <= maxDistance + 1.0E-8D;
             distance = Math.min(maxDistance, distance + SEARCH_STEP)) {
            Location sample = start.clone().add(direction.clone().multiply(distance));
            BoundingBox body = player.getBoundingBox().clone();
            body.shift(
                    sample.getX() - start.getX(),
                    sample.getY() - start.getY(),
                    sample.getZ() - start.getZ()
            );
            boolean loaded = true;
            for (int blockX = (int) Math.floor(body.getMinX());
                 blockX <= (int) Math.floor(body.getMaxX()) && loaded;
                 blockX++) {
                for (int blockZ = (int) Math.floor(body.getMinZ());
                     blockZ <= (int) Math.floor(body.getMaxZ());
                     blockZ++) {
                    if (!worldLoaded(sample, blockX, blockZ)) {
                        loaded = false;
                        break;
                    }
                }
            }
            if (!loaded) {
                break;
            }
            furthestLoaded = distance;
            if (distance >= maxDistance) {
                break;
            }
        }
        return furthestLoaded;
    }

    private boolean worldLoaded(@NotNull Location location, int blockX, int blockZ) {
        World world = location.getWorld();
        return world != null && world.isChunkLoaded(blockX >> 4, blockZ >> 4);
    }

    private double firstBodyCollisionDistance(
            @NotNull Player player,
            @NotNull Location start,
            @NotNull Vector direction,
            double maxDistance
    ) {
        World world = start.getWorld();
        if (world == null || maxDistance <= 0.0D) {
            return Double.POSITIVE_INFINITY;
        }

        BoundingBox body = player.getBoundingBox();
        double minOffsetX = body.getMinX() - start.getX();
        double maxOffsetX = body.getMaxX() - start.getX();
        double minOffsetY = body.getMinY() - start.getY();
        double maxOffsetY = body.getMaxY() - start.getY();
        double minOffsetZ = body.getMinZ() - start.getZ();
        double maxOffsetZ = body.getMaxZ() - start.getZ();
        BoundingBox endBody = body.clone();
        endBody.shift(direction.clone().multiply(maxDistance));
        BoundingBox sweepBounds = new BoundingBox(
                Math.min(body.getMinX(), endBody.getMinX()),
                Math.min(body.getMinY(), endBody.getMinY()),
                Math.min(body.getMinZ(), endBody.getMinZ()),
                Math.max(body.getMaxX(), endBody.getMaxX()),
                Math.max(body.getMaxY(), endBody.getMaxY()),
                Math.max(body.getMaxZ(), endBody.getMaxZ())
        );

        double nearestCollision = Double.POSITIVE_INFINITY;
        for (int blockX = (int) Math.floor(sweepBounds.getMinX());
             blockX <= (int) Math.floor(sweepBounds.getMaxX());
             blockX++) {
            // フェンスなどの衝突形状はブロックの高さを越えるため、上下も隣接ブロックを調べます。
            for (int blockY = (int) Math.floor(sweepBounds.getMinY()) - 1;
                 blockY <= (int) Math.floor(sweepBounds.getMaxY()) + 1;
                 blockY++) {
                for (int blockZ = (int) Math.floor(sweepBounds.getMinZ());
                     blockZ <= (int) Math.floor(sweepBounds.getMaxZ());
                     blockZ++) {
                    if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
                        continue;
                    }
                    Block block = world.getBlockAt(blockX, blockY, blockZ);
                    for (BoundingBox localShape : block.getCollisionShape().getBoundingBoxes()) {
                        BoundingBox shapeBox = localShape.clone().shift(blockX, blockY, blockZ);
                        BoundingBox expandedShape = new BoundingBox(
                                shapeBox.getMinX() - maxOffsetX,
                                shapeBox.getMinY() - maxOffsetY,
                                shapeBox.getMinZ() - maxOffsetZ,
                                shapeBox.getMaxX() - minOffsetX,
                                shapeBox.getMaxY() - minOffsetY,
                                shapeBox.getMaxZ() - minOffsetZ
                        );
                        double collisionDistance = rayIntersectionDistance(
                                start,
                                direction,
                                expandedShape,
                                maxDistance
                        );
                        nearestCollision = Math.min(nearestCollision, collisionDistance);
                    }
                }
            }
        }
        return nearestCollision;
    }

    private static double rayIntersectionDistance(
            @NotNull Location start,
            @NotNull Vector direction,
            @NotNull BoundingBox box,
            double maxDistance
    ) {
        double entryDistance = 0.0D;
        double exitDistance = maxDistance;
        double[] origin = {start.getX(), start.getY(), start.getZ()};
        double[] directionComponents = {direction.getX(), direction.getY(), direction.getZ()};
        double[] min = {box.getMinX(), box.getMinY(), box.getMinZ()};
        double[] max = {box.getMaxX(), box.getMaxY(), box.getMaxZ()};

        for (int axis = 0; axis < origin.length; axis++) {
            double component = directionComponents[axis];
            if (Math.abs(component) <= 1.0E-8D) {
                if (origin[axis] <= min[axis] + 1.0E-8D
                        || origin[axis] >= max[axis] - 1.0E-8D) {
                    return Double.POSITIVE_INFINITY;
                }
                continue;
            }

            double first = (min[axis] - origin[axis]) / component;
            double second = (max[axis] - origin[axis]) / component;
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
            }
            entryDistance = Math.max(entryDistance, first);
            exitDistance = Math.min(exitDistance, second);
            if (entryDistance >= exitDistance) {
                return Double.POSITIVE_INFINITY;
            }
        }

        return exitDistance <= 1.0E-8D || entryDistance > maxDistance
                ? Double.POSITIVE_INFINITY
                : Math.max(0.0D, entryDistance);
    }

    private boolean isBlinkBodyClear(@NotNull Player player, @NotNull Location destination) {
        World world = destination.getWorld();
        if (world == null) {
            return false;
        }
        Location start = player.getLocation();
        BoundingBox body = player.getBoundingBox().clone();
        body.shift(
                destination.getX() - start.getX(),
                destination.getY() - start.getY(),
                destination.getZ() - start.getZ()
        );

        int minBlockX = (int) Math.floor(body.getMinX());
        int maxBlockX = (int) Math.floor(body.getMaxX());
        int minBlockY = (int) Math.floor(body.getMinY()) - 1;
        int maxBlockY = (int) Math.floor(body.getMaxY()) + 1;
        int minBlockZ = (int) Math.floor(body.getMinZ());
        int maxBlockZ = (int) Math.floor(body.getMaxZ());
        for (int blockX = minBlockX; blockX <= maxBlockX; blockX++) {
            for (int blockY = minBlockY; blockY <= maxBlockY; blockY++) {
                for (int blockZ = minBlockZ; blockZ <= maxBlockZ; blockZ++) {
                    if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
                        return false;
                    }
                    Block block = world.getBlockAt(blockX, blockY, blockZ);
                    for (BoundingBox localShape : block.getCollisionShape().getBoundingBoxes()) {
                        if (localShape.clone().shift(blockX, blockY, blockZ).overlaps(body)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
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

    private static boolean isFinite(@NotNull Vector vector) {
        return Double.isFinite(vector.getX())
                && Double.isFinite(vector.getY())
                && Double.isFinite(vector.getZ());
    }
}
