package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillLineTargetHit;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
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

import java.util.ArrayList;
import java.util.Collections;
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
    private static final double HEIGHT_BOUNDARY_EPSILON = 1.0E-6D;
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
     * 視線方向を軸にした薙ぎ払いの角度範囲内にいる対象を返します。
     * <p>
     * 対象中心の視線軸からの角度と上下方向のずれを確認するため、
     * 視点の高さと pitch を反映した薙ぎ払い判定に利用できます。
     *
     * @param player 発動者
     * @param origin 発動時の視点位置
     * @param direction 発動時の視線方向
     * @param range 最大射程
     * @param startAngleDegrees 判定開始角度。正の角度は視線から見て右側
     * @param endAngleDegrees 判定終了角度
     * @param maxTargets 最大対象数
     * @param requireLineOfSight 遮蔽判定を行うか
     * @return 指定角度範囲内の対象
     */
    public @NotNull List<AstEntity> inViewArcSegment(
            @NotNull Player player,
            @NotNull Location origin,
            @NotNull Vector direction,
            double range,
            double startAngleDegrees,
            double endAngleDegrees,
            int maxTargets,
            boolean requireLineOfSight
    ) {
        if (origin.getWorld() != player.getWorld()) {
            return List.of();
        }
        Vector forward = normalized(direction);
        Vector right = viewRight(forward);
        Vector up = right.clone().crossProduct(forward).normalize();
        double minAngle = Math.toRadians(Math.min(startAngleDegrees, endAngleDegrees));
        double maxAngle = Math.toRadians(Math.max(startAngleDegrees, endAngleDegrees));
        double rangeSquared = Math.max(0.0D, range) * Math.max(0.0D, range);
        return targets(player, mob -> {
            Location center = targetCenter(mob);
            Vector offset = center.toVector().subtract(origin.toVector());
            double distanceSquared = offset.lengthSquared();
            if (distanceSquared > rangeSquared || distanceSquared <= 1.0E-8D) {
                return false;
            }
            double forwardDistance = offset.dot(forward);
            if (forwardDistance <= 0.0D) {
                return false;
            }
            double angle = Math.atan2(offset.dot(right), forwardDistance);
            return angle >= minAngle
                    && angle <= maxAngle
                    && Math.abs(offset.dot(up)) <= 1.35D
                    && (!requireLineOfSight || hasLineOfSight(origin, center));
        }).stream()
                .sorted(Comparator.comparingDouble(target ->
                        targetCenter(target).distanceSquared(origin)))
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
        return inLineWithinRange(player, origin, normalizedDirection, clippedRange, radius, maxTargets);
    }

    /**
     * 指定した Block 衝突面より前だけを swept capsule で検索します。
     * <p>
     * Block と同一距離の Mob は Block 衝突を優先するため、終端は含めません。
     *
     * @param player 発動者
     * @param origin 始点
     * @param direction 進行方向
     * @param blockImpactDistance Block 衝突面までの距離
     * @param radius 当たり判定半径
     * @param maxTargets 最大対象数
     * @return Block 衝突面より手前の命中対象
     */
    public @NotNull List<AstEntity> inLineBeforeBlock(
            @NotNull Player player,
            @NotNull Location origin,
            @NotNull Vector direction,
            double blockImpactDistance,
            double radius,
            int maxTargets
    ) {
        if (origin.getWorld() != player.getWorld()) {
            return List.of();
        }
        return inLineWithinRange(
                player,
                origin,
                normalized(direction),
                Math.nextDown(Math.max(0.0D, blockImpactDistance)),
                radius,
                maxTargets
        );
    }

    private @NotNull List<AstEntity> inLineWithinRange(
            @NotNull Player player,
            @NotNull Location origin,
            @NotNull Vector normalizedDirection,
            double range,
            double radius,
            int maxTargets
    ) {
        return targets(player, mob -> {
            return Double.isFinite(lineIntersectionDistance(
                    targetBounds(mob),
                    origin.toVector(),
                    normalizedDirection,
                    range,
                    radius
            ));
        }).stream()
                .sorted(Comparator
                        .comparingDouble((MobInstance mob) -> lineIntersectionDistance(
                                targetBounds(mob),
                                origin.toVector(),
                                normalizedDirection,
                                range,
                                radius
                        ))
                        .thenComparing(mob -> mob.instanceId().toString()))
                .limit(Math.max(0, maxTargets))
                .map(AstEntity::mob)
                .toList();
    }

    /**
     * 既にBlock衝突範囲が解決された線分から、Mobと最初に交差する正確な地点を返します。
     *
     * @param player 発動者
     * @param origin 線分始点
     * @param direction 線分方向
     * @param range 判定距離
     * @param radius 飛翔体半径
     * @param maxTargets 最大対象数
     * @param includeRangeEnd 線分終端と同距離の交差を含めるか。Block面ではfalse
     * @return 交差距離順の対象と交点
     */
    public @NotNull List<SkillLineTargetHit> lineTargetHits(
            @NotNull Player player,
            @NotNull Location origin,
            @NotNull Vector direction,
            double range,
            double radius,
            int maxTargets,
            boolean includeRangeEnd
    ) {
        return lineTargetHits(
                player,
                captureLineTargetSnapshot(player),
                origin,
                direction,
                range,
                radius,
                maxTargets,
                includeRangeEnd
        );
    }

    /**
     * 同一tick内の複数線分判定で共有するMob候補とbody boundsを取得します。
     *
     * @param player 発動者
     * @return 取得時点の同一world内にいる有効Mob候補
     */
    public @NotNull LineTargetSnapshot captureLineTargetSnapshot(@NotNull Player player) {
        World world = player.getWorld();
        List<LineTargetCandidate> candidates = mobService.getInstances().stream()
                .filter(mob -> mob.state() != MobState.DEAD)
                .filter(mob -> mob.template().category() != MobCategory.NPC)
                .filter(mob -> mob.currentLocation().getWorld() == world)
                .map(mob -> new LineTargetCandidate(mob, targetBounds(mob)))
                .toList();
        return new LineTargetSnapshot(world, candidates);
    }

    /**
     * 共有snapshotを使い、Block衝突範囲が解決された線分と交差するMobを返します。
     *
     * @param player 発動者
     * @param snapshot 同一tick内に取得したMob候補snapshot
     * @param origin 線分始点
     * @param direction 線分方向
     * @param range 判定距離
     * @param radius 飛翔体半径
     * @param maxTargets 最大対象数
     * @param includeRangeEnd 線分終端と同距離の交差を含めるか。Block面ではfalse
     * @return 交差距離順の対象と交点
     */
    public @NotNull List<SkillLineTargetHit> lineTargetHits(
            @NotNull Player player,
            @NotNull LineTargetSnapshot snapshot,
            @NotNull Location origin,
            @NotNull Vector direction,
            double range,
            double radius,
            int maxTargets,
            boolean includeRangeEnd
    ) {
        if (origin.getWorld() != player.getWorld() || range <= 0.0D || maxTargets <= 0) {
            return List.of();
        }
        if (snapshot.world != player.getWorld()) {
            return List.of();
        }
        Vector normalizedDirection = normalized(direction);
        Vector originVector = origin.toVector();
        List<MobLineIntersection> nearest = new ArrayList<>(Math.min(maxTargets, snapshot.candidates.size()));
        for (LineTargetCandidate candidate : snapshot.candidates) {
            MobInstance mob = candidate.mob();
            if (mobService.getInstance(mob.instanceId()) != mob
                    || mob.state() == MobState.DEAD
                    || mob.template().category() == MobCategory.NPC
                    || mob.currentLocation().getWorld() != player.getWorld()) {
                continue;
            }
            double distance = lineIntersectionDistance(
                    candidate.bounds(), originVector, normalizedDirection, range, radius
            );
            if (!Double.isFinite(distance) || (!includeRangeEnd && distance + 1.0E-6D >= range)) {
                continue;
            }
            MobLineIntersection hit = new MobLineIntersection(mob, distance);
            int insertionIndex = Collections.binarySearch(nearest, hit, MOB_LINE_INTERSECTION_ORDER);
            insertionIndex = insertionIndex < 0 ? -insertionIndex - 1 : insertionIndex;
            if (insertionIndex >= maxTargets) {
                continue;
            }
            nearest.add(insertionIndex, hit);
            if (nearest.size() > maxTargets) {
                nearest.removeLast();
            }
        }

        List<SkillLineTargetHit> hits = new ArrayList<>(nearest.size());
        for (MobLineIntersection hit : nearest) {
            hits.add(new SkillLineTargetHit(
                    AstEntity.mob(hit.mob()),
                    origin.clone().add(normalizedDirection.clone().multiply(hit.distance())),
                    hit.distance()
            ));
        }
        return List.copyOf(hits);
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
     * 指定地点の円柱範囲へ入っているゲームプレイ中のプレイヤーを返します。
     * <p>
     * Mob用の {@link #inRadius(Player, Location, double, double, int, boolean)} と異なり、
     * Bukkitのオンラインプレイヤーをキャッシュ済みの {@link AstPlayer} へ解決します。
     * 水平方向は半径、上下方向は中心からの許容差で判定します。
     *
     * @param center 中心
     * @param radius 水平半径
     * @param height 上下の許容差
     * @return 範囲内のゲームプレイ中プレイヤー
     */
    public @NotNull List<AstPlayer> playersInRadius(
            @NotNull Location center,
            double radius,
            double height
    ) {
        World world = center.getWorld();
        double safeRadius = Math.max(0.0D, radius);
        double safeHeight = Math.max(0.0D, height);
        if (world == null) {
            return List.of();
        }
        double radiusSquared = safeRadius * safeRadius;
        return Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.isOnline() && !player.isDead() && player.getWorld() == world)
                .filter(player -> {
                    Location location = player.getLocation();
                    double dx = location.getX() - center.getX();
                    double dz = location.getZ() - center.getZ();
                    return dx * dx + dz * dz <= radiusSquared
                            && location.getY() >= center.getY() - safeHeight
                            && location.getY() <= center.getY() + safeHeight;
                })
                .map(AstPlayerCache::get)
                .filter(AccountModeGuard::isGameplayPlayer)
                .sorted(Comparator.comparing(player -> player.getBukkit().getUniqueId().toString()))
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
        Vector normalizedDirection = normalized(direction);
        double safeRange = Math.max(0.0D, range);
        if (origin.getWorld() == null || safeRange <= 0.0D) {
            return origin.clone();
        }
        Location impact = blockImpact(origin, normalizedDirection, safeRange);
        if (impact == null) {
            return origin.clone().add(normalizedDirection.multiply(safeRange));
        }
        double hitDistance = impact.distance(origin);
        return origin.clone().add(normalizedDirection.multiply(Math.max(0.0D, hitDistance - 0.1D)));
    }

    /**
     * 指定線分で最初に衝突するブロックの正確な地点を返します。
     *
     * @param origin 始点
     * @param direction 進行方向
     * @param range 最大距離
     * @return ブロックへ衝突する地点。衝突しない場合は null
     */
    public @Nullable Location blockImpact(
            @NotNull Location origin,
            @NotNull Vector direction,
            double range
    ) {
        World world = origin.getWorld();
        double safeRange = Math.max(0.0D, range);
        if (world == null || safeRange <= 0.0D) {
            return null;
        }
        Vector normalizedDirection = normalized(direction);
        RayTraceResult hit = world.rayTraceBlocks(
                origin,
                normalizedDirection,
                safeRange,
                FluidCollisionMode.NEVER,
                true
        );
        if (hit == null || hit.getHitPosition() == null) {
            return null;
        }
        return hit.getHitPosition().toLocation(world);
    }

    /**
     * 指定高度より低い線分区間だけで最初に衝突するブロックの正確な地点を返します。
     *
     * @param origin 始点
     * @param direction 進行方向
     * @param range 最大距離
     * @param exclusiveMaximumY 衝突判定を有効にするY座標の排他的上限
     * @return 上限より低い位置でブロックへ衝突する地点。衝突しない場合はnull
     */
    public @Nullable Location blockImpactBelowY(
            @NotNull Location origin,
            @NotNull Vector direction,
            double range,
            double exclusiveMaximumY
    ) {
        double safeRange = Math.max(0.0D, range);
        if (origin.getWorld() == null || safeRange <= 0.0D || !Double.isFinite(exclusiveMaximumY)) {
            return null;
        }
        Vector normalizedDirection = normalized(direction);
        double directionY = normalizedDirection.getY();
        double originY = origin.getY();
        double endY = originY + directionY * safeRange;

        if (originY < exclusiveMaximumY) {
            if (directionY <= 0.0D || endY < exclusiveMaximumY) {
                return blockImpact(origin, normalizedDirection, safeRange);
            }
            double boundaryDistance = (exclusiveMaximumY - originY) / directionY;
            return blockImpact(origin, normalizedDirection, Math.nextDown(boundaryDistance));
        }
        if (directionY >= 0.0D || endY >= exclusiveMaximumY) {
            return null;
        }

        double boundaryDistance = (exclusiveMaximumY - originY) / directionY;
        double startDistance = boundaryDistance + HEIGHT_BOUNDARY_EPSILON / -directionY;
        if (startDistance >= safeRange) {
            return null;
        }
        Location clippedOrigin = origin.clone().add(normalizedDirection.clone().multiply(startDistance));
        return blockImpact(clippedOrigin, normalizedDirection, safeRange - startDistance);
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
        return groundAt(end, 3, 32);
    }

    /**
     * 指定した水平位置の周囲から、矢や範囲演出が到達できる地表を探します。
     *
     * @param probe 水平位置と探索基準Y
     * @param searchUp 基準Yより上へ探す最大block数
     * @param searchDown 基準Yより下へ探す最大block数
     * @return 足元が固体で足位置が通過可能な地表。見つからない場合は入力位置の複製
     */
    public @NotNull Location groundAt(
            @NotNull Location probe,
            int searchUp,
            int searchDown
    ) {
        Location fallback = probe.clone();
        World world = probe.getWorld();
        if (world == null) {
            return fallback;
        }
        int startY = Math.min(world.getMaxHeight() - 2, probe.getBlockY() + Math.max(0, searchUp));
        int minY = Math.max(world.getMinHeight() + 1, probe.getBlockY() - Math.max(0, searchDown));
        for (int y = startY; y >= minY; y--) {
            Block floor = world.getBlockAt(probe.getBlockX(), y - 1, probe.getBlockZ());
            Block feet = world.getBlockAt(probe.getBlockX(), y, probe.getBlockZ());
            if (!floor.isPassable() && feet.isPassable()) {
                return new Location(world, probe.getX(), y + 0.05D, probe.getZ());
            }
        }
        return fallback;
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

    private static @NotNull Vector viewRight(@NotNull Vector forward) {
        Vector right = forward.clone().crossProduct(new Vector(0.0D, 1.0D, 0.0D));
        if (right.lengthSquared() <= 1.0E-8D) {
            return new Vector(1.0D, 0.0D, 0.0D);
        }
        return right.normalize();
    }

    private static double horizontalDistanceSquared(@NotNull Location first, @NotNull Location second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static final Comparator<MobLineIntersection> MOB_LINE_INTERSECTION_ORDER = Comparator
            .comparingDouble(MobLineIntersection::distance)
            .thenComparing(hit -> hit.mob().instanceId().toString());

    /** 同一tick内の線分判定で共有するMob候補snapshotです。 */
    public static final class LineTargetSnapshot {
        private final World world;
        private final List<LineTargetCandidate> candidates;

        private LineTargetSnapshot(@NotNull World world, @NotNull List<LineTargetCandidate> candidates) {
            this.world = world;
            this.candidates = candidates;
        }
    }

    private record LineTargetCandidate(@NotNull MobInstance mob, @NotNull BoundingBox bounds) {
    }

    private record MobLineIntersection(@NotNull MobInstance mob, double distance) {
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
