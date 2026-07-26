package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * プレイヤー発動スキルの Mob 当たり判定を一元化します。
 * <p>
 * Bukkit の表示 Entity ではなく {@link MobInstance} の現在位置を正本にするため、
 * 見た目の実装が変わっても個別スキルの判定ロジックを変更せずに済みます。
 */
public final class SkillTargetingService {

    private static final double FALLBACK_TARGET_HALF_WIDTH = 0.45D;
    private static final double FALLBACK_TARGET_HEIGHT = 1.8D;
    private final MobService mobService;

    /**
     * Mob サービスを使って構築します。
     *
     * @param mobService 現在存在する Mob の取得元
     */
    public SkillTargetingService(@NotNull MobService mobService) {
        this.mobService = mobService;
    }

    /**
     * 視線を中心とした扇形の対象を近い順で返します。
     *
     * @param player 発動者
     * @param range 射程
     * @param angleDegrees 扇形の全角
     * @param maxTargets 最大対象数
     * @param requireLineOfSight 遮蔽判定を行うか
     * @return 命中対象
     */
    public @NotNull List<AstEntity> inCone(
            @NotNull Player player,
            double range,
            double angleDegrees,
            int maxTargets,
            boolean requireLineOfSight
    ) {
        Location origin = player.getEyeLocation();
        Vector direction = normalized(origin.getDirection());
        double minDot = Math.cos(Math.toRadians(Math.clamp(angleDegrees, 0.0D, 360.0D) * 0.5D));
        return targets(player, mob -> {
            Location center = targetCenter(mob);
            Vector offset = center.toVector().subtract(origin.toVector());
            double distanceSquared = offset.lengthSquared();
            if (distanceSquared > range * range || distanceSquared <= 1.0E-8D) {
                return false;
            }
            return normalized(offset).dot(direction) >= minDot
                    && (!requireLineOfSight || hasLineOfSight(origin, center));
        }).stream()
                .sorted(Comparator.comparingDouble(target ->
                        target.currentLocation().distanceSquared(player.getLocation())))
                .limit(Math.max(0, maxTargets))
                .map(AstEntity::mob)
                .toList();
    }

    /**
     * 指定線分を中心とする capsule 内の対象を進行方向順で返します。
     *
     * @param player 発動者
     * @param origin 始点
     * @param direction 進行方向
     * @param range 最大距離
     * @param radius 当たり判定半径
     * @param maxTargets 最大対象数
     * @return 命中対象
     */
    public @NotNull List<AstEntity> inLine(
            @NotNull Player player,
            @NotNull Location origin,
            @NotNull Vector direction,
            double range,
            double radius,
            int maxTargets
    ) {
        if (origin.getWorld() != player.getWorld()) {
            return List.of();
        }
        Vector normalizedDirection = normalized(direction);
        double clippedRange = origin.distance(clippedEnd(origin, normalizedDirection, range));
        return targets(player, mob -> {
            return Double.isFinite(lineIntersectionDistance(
                    targetBounds(mob),
                    origin.toVector(),
                    normalizedDirection,
                    clippedRange,
                    radius
            ));
        }).stream()
                .sorted(Comparator
                        .comparingDouble((MobInstance mob) -> lineIntersectionDistance(
                                targetBounds(mob),
                                origin.toVector(),
                                normalizedDirection,
                                clippedRange,
                                radius
                        ))
                        .thenComparing(mob -> mob.instanceId().toString()))
                .limit(Math.max(0, maxTargets))
                .map(AstEntity::mob)
                .toList();
    }

    /**
     * 円柱範囲内の対象を中心から近い順で返します。
     *
     * @param player 発動者
     * @param center 中心
     * @param radius 水平半径
     * @param height 上下の許容差
     * @param maxTargets 最大対象数
     * @param requireLineOfSight 遮蔽判定を行うか
     * @return 命中対象
     */
    public @NotNull List<AstEntity> inRadius(
            @NotNull Player player,
            @NotNull Location center,
            double radius,
            double height,
            int maxTargets,
            boolean requireLineOfSight
    ) {
        if (center.getWorld() != player.getWorld()) {
            return List.of();
        }
        double radiusSquared = radius * radius;
        return targets(player, mob -> {
            BoundingBox bounds = targetBounds(mob);
            double nearestX = Math.clamp(center.getX(), bounds.getMinX(), bounds.getMaxX());
            double nearestZ = Math.clamp(center.getZ(), bounds.getMinZ(), bounds.getMaxZ());
            double dx = nearestX - center.getX();
            double dz = nearestZ - center.getZ();
            Location targetCenter = targetCenter(mob);
            return dx * dx + dz * dz <= radiusSquared
                    && bounds.getMaxY() >= center.getY() - height
                    && bounds.getMinY() <= center.getY() + height
                    && (!requireLineOfSight || hasLineOfSight(center, targetCenter));
        }).stream()
                .sorted(Comparator
                        .comparingDouble((MobInstance mob) -> horizontalDistanceSquared(targetCenter(mob), center))
                        .thenComparing(mob -> mob.instanceId().toString()))
                .limit(Math.max(0, maxTargets))
                .map(AstEntity::mob)
                .toList();
    }

    /**
     * 中心付近から除外対象を除いた最寄り Mob を返します。
     *
     * @param player 発動者
     * @param center 探索中心
     * @param radius 探索半径
     * @param excluded 除外する combat entity ID
     * @return 最寄り対象。存在しない場合は null
     */
    public @Nullable AstEntity nearestFrom(
            @NotNull Player player,
            @NotNull Location center,
            double radius,
            @NotNull Set<UUID> excluded
    ) {
        return inRadius(player, center, radius, radius, Integer.MAX_VALUE, true).stream()
                .filter(target -> !excluded.contains(target.id()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 視線上のブロックで射程を切り、終点を返します。
     *
     * @param origin 始点
     * @param direction 進行方向
     * @param range 最大距離
     * @return 遮蔽を考慮した終点
     */
    public @NotNull Location clippedEnd(
            @NotNull Location origin,
            @NotNull Vector direction,
            double range
    ) {
        World world = origin.getWorld();
        Vector normalizedDirection = normalized(direction);
        double safeRange = Math.max(0.0D, range);
        if (world == null || safeRange <= 0.0D) {
            return origin.clone();
        }
        RayTraceResult hit = world.rayTraceBlocks(
                origin,
                normalizedDirection,
                safeRange,
                FluidCollisionMode.NEVER,
                true
        );
        if (hit == null || hit.getHitPosition() == null) {
            return origin.clone().add(normalizedDirection.multiply(safeRange));
        }
        double hitDistance = hit.getHitPosition().distance(origin.toVector());
        return origin.clone().add(normalizedDirection.multiply(Math.max(0.0D, hitDistance - 0.1D)));
    }

    /**
     * 視線先の地面位置を返します。
     *
     * @param player 発動者
     * @param range 最大距離
     * @return 見つかった地面。存在しない場合は射程終点
     */
    public @NotNull Location groundTarget(@NotNull Player player, double range) {
        Location eye = player.getEyeLocation();
        Location end = clippedEnd(eye, eye.getDirection(), range);
        World world = end.getWorld();
        if (world == null) {
            return end;
        }
        int startY = Math.min(world.getMaxHeight() - 2, end.getBlockY() + 3);
        int minY = Math.max(world.getMinHeight() + 1, end.getBlockY() - 32);
        for (int y = startY; y >= minY; y--) {
            Block floor = world.getBlockAt(end.getBlockX(), y - 1, end.getBlockZ());
            Block feet = world.getBlockAt(end.getBlockX(), y, end.getBlockZ());
            if (!floor.isPassable() && feet.isPassable()) {
                return new Location(world, end.getX(), y + 0.05D, end.getZ());
            }
        }
        return end;
    }

    /**
     * 2点間に遮蔽ブロックがないか判定します。
     *
     * @param from 始点
     * @param to 終点
     * @return 見通せる場合は true
     */
    public boolean hasLineOfSight(@NotNull Location from, @NotNull Location to) {
        if (from.getWorld() == null || from.getWorld() != to.getWorld()) {
            return false;
        }
        Vector offset = to.toVector().subtract(from.toVector());
        double distance = offset.length();
        if (distance <= 1.0E-8D) {
            return true;
        }
        RayTraceResult hit = from.getWorld().rayTraceBlocks(
                from,
                offset.normalize(),
                distance,
                FluidCollisionMode.NEVER,
                true
        );
        return hit == null;
    }

    private @NotNull List<MobInstance> targets(
            @NotNull Player player,
            @NotNull Predicate<MobInstance> shape
    ) {
        World world = player.getWorld();
        return mobService.getInstances().stream()
                .filter(mob -> mob.state() != MobState.DEAD)
                .filter(mob -> mob.template().category() != MobCategory.NPC)
                .filter(mob -> mob.currentLocation().getWorld() == world)
                .filter(shape)
                .toList();
    }

    private static @NotNull Location targetCenter(@NotNull MobInstance mob) {
        Location location = mob.currentLocation();
        Vector center = targetBounds(mob).getCenter();
        return new Location(location.getWorld(), center.getX(), center.getY(), center.getZ());
    }

    private static @NotNull BoundingBox targetBounds(@NotNull MobInstance mob) {
        if (mob.bukkitEntityId() != null) {
            Entity entity = Bukkit.getEntity(mob.bukkitEntityId());
            if (entity != null && entity.isValid()) {
                return entity.getBoundingBox();
            }
        }
        Location location = mob.currentLocation();
        return new BoundingBox(
                location.getX() - FALLBACK_TARGET_HALF_WIDTH,
                location.getY(),
                location.getZ() - FALLBACK_TARGET_HALF_WIDTH,
                location.getX() + FALLBACK_TARGET_HALF_WIDTH,
                location.getY() + FALLBACK_TARGET_HEIGHT,
                location.getZ() + FALLBACK_TARGET_HALF_WIDTH
        );
    }

    private static @NotNull Vector normalized(@NotNull Vector vector) {
        return vector.lengthSquared() <= 1.0E-8D
                ? new Vector(0.0D, 0.0D, 1.0D)
                : vector.clone().normalize();
    }

    private static double horizontalDistanceSquared(@NotNull Location first, @NotNull Location second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    static boolean intersectsLine(
            @NotNull BoundingBox bounds,
            @NotNull Vector origin,
            @NotNull Vector direction,
            double range,
            double radius
    ) {
        return Double.isFinite(lineIntersectionDistance(bounds, origin, direction, range, radius));
    }

    static double lineIntersectionDistance(
            @NotNull BoundingBox bounds,
            @NotNull Vector origin,
            @NotNull Vector direction,
            double range,
            double radius
    ) {
        BoundingBox expanded = bounds.clone().expand(Math.max(0.0D, radius));
        if (expanded.contains(origin)) {
            return 0.0D;
        }
        Vector normalizedDirection = normalized(direction);
        RayTraceResult hit = expanded.rayTrace(origin, normalizedDirection, Math.max(0.0D, range));
        return hit == null || hit.getHitPosition() == null
                ? Double.POSITIVE_INFINITY
                : hit.getHitPosition().distance(origin);
    }
}
