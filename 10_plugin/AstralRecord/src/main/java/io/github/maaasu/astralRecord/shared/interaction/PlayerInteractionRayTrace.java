package io.github.maaasu.astralRecord.shared.interaction;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * プレイヤー入力候補で共有する、有限長かつ正規化済みの視線 ray です。
 *
 * <p>命中時は ray 始点から hitbox 入口までの有限な非負距離を返し、
 * hitbox が始点を含む場合は {@code 0.0D} を返します。無効な形状または
 * 最大距離内に入口がない場合は {@code null} を返します。</p>
 */
public final class PlayerInteractionRayTrace {
    private static final double DIRECTION_EPSILON_SQUARED = 1.0E-16D;
    private static final double PARALLEL_EPSILON = 1.0E-12D;
    private static final double DISTANCE_EPSILON = 1.0E-9D;

    private final Vector origin;
    private final Vector direction;
    private final double maxDistance;

    private PlayerInteractionRayTrace(
            @NotNull Vector origin,
            @NotNull Vector direction,
            double maxDistance
    ) {
        this.origin = origin;
        this.direction = direction;
        this.maxDistance = maxDistance;
    }

    /**
     * 始点と方向から正規化済みの有限長 ray を生成します。
     *
     * @param origin ray の始点
     * @param direction ray の方向。長さは任意
     * @param maxDistance 判定する最大距離。0 以上の有限値
     * @return 正規化済み ray。入力が非有限、方向がゼロ、最大距離が不正な場合は {@code null}
     */
    @Nullable
    public static PlayerInteractionRayTrace create(
            @NotNull Vector origin,
            @NotNull Vector direction,
            double maxDistance
    ) {
        if (!isFinite(origin) || !isFinite(direction)
                || !Double.isFinite(maxDistance) || maxDistance < 0.0D) {
            return null;
        }
        double directionLengthSquared = direction.lengthSquared();
        if (!Double.isFinite(directionLengthSquared)
                || directionLengthSquared <= DIRECTION_EPSILON_SQUARED) {
            return null;
        }
        Vector normalizedDirection = direction.clone().multiply(1.0D / Math.sqrt(directionLengthSquared));
        if (!isFinite(normalizedDirection)) {
            return null;
        }
        return new PlayerInteractionRayTrace(origin.clone(), normalizedDirection, maxDistance);
    }

    /**
     * ray 始点のコピーを返します。
     *
     * @return ray 始点
     */
    @NotNull
    public Vector origin() {
        return origin.clone();
    }

    /**
     * 長さ1に正規化された ray 方向のコピーを返します。
     *
     * @return 正規化済み方向
     */
    @NotNull
    public Vector direction() {
        return direction.clone();
    }

    /**
     * 判定対象の最大距離を返します。
     *
     * @return 0 以上の有限な最大距離
     */
    public double maxDistance() {
        return maxDistance;
    }

    /**
     * 球と ray の最初の交点までの距離を返します。
     *
     * @param center 球の中心
     * @param radius 球の半径。0 以上の有限値
     * @return 最大距離内の入口距離。命中しない、または形状が不正な場合は {@code null}
     */
    @Nullable
    public Double sphereEntryDistance(@NotNull Vector center, double radius) {
        if (!isFinite(center) || !Double.isFinite(radius) || radius < 0.0D) {
            return null;
        }

        Vector originToCenter = center.clone().subtract(origin);
        double centerDistanceSquared = originToCenter.lengthSquared();
        double radiusSquared = radius * radius;
        if (!Double.isFinite(centerDistanceSquared) || !Double.isFinite(radiusSquared)) {
            return null;
        }
        if (centerDistanceSquared <= radiusSquared) {
            return 0.0D;
        }

        double projection = originToCenter.dot(direction);
        if (!Double.isFinite(projection) || projection < 0.0D) {
            return null;
        }
        double perpendicularSquared = centerDistanceSquared - projection * projection;
        if (!Double.isFinite(perpendicularSquared)) {
            return null;
        }
        double entryOffsetSquared = radiusSquared - perpendicularSquared;
        if (entryOffsetSquared < -DISTANCE_EPSILON) {
            return null;
        }
        double entryDistance = projection - Math.sqrt(Math.max(0.0D, entryOffsetSquared));
        return boundedDistance(entryDistance);
    }

    /**
     * 軸平行境界箱と ray の最初の交点までの距離を返します。
     *
     * @param boundingBox 判定する軸平行境界箱
     * @return 最大距離内の入口距離。命中しない、または境界値が不正な場合は {@code null}
     */
    @Nullable
    public Double aabbEntryDistance(@NotNull BoundingBox boundingBox) {
        double[] originValues = {origin.getX(), origin.getY(), origin.getZ()};
        double[] directionValues = {direction.getX(), direction.getY(), direction.getZ()};
        double[] minValues = {boundingBox.getMinX(), boundingBox.getMinY(), boundingBox.getMinZ()};
        double[] maxValues = {boundingBox.getMaxX(), boundingBox.getMaxY(), boundingBox.getMaxZ()};

        double entryDistance = 0.0D;
        double exitDistance = maxDistance;
        for (int axis = 0; axis < 3; axis++) {
            double axisOrigin = originValues[axis];
            double axisDirection = directionValues[axis];
            double axisMin = minValues[axis];
            double axisMax = maxValues[axis];
            if (!Double.isFinite(axisMin) || !Double.isFinite(axisMax) || axisMin > axisMax) {
                return null;
            }
            if (Math.abs(axisDirection) <= PARALLEL_EPSILON) {
                if (axisOrigin < axisMin || axisOrigin > axisMax) {
                    return null;
                }
                continue;
            }

            double first = (axisMin - axisOrigin) / axisDirection;
            double second = (axisMax - axisOrigin) / axisDirection;
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
            }
            entryDistance = Math.max(entryDistance, first);
            exitDistance = Math.min(exitDistance, second);
            if (entryDistance - exitDistance > DISTANCE_EPSILON) {
                return null;
            }
        }
        return boundedDistance(entryDistance);
    }

    @Nullable
    private Double boundedDistance(double distance) {
        if (!Double.isFinite(distance) || distance < -DISTANCE_EPSILON
                || distance - maxDistance > DISTANCE_EPSILON) {
            return null;
        }
        return Math.max(0.0D, Math.min(distance, maxDistance));
    }

    private static boolean isFinite(@NotNull Vector vector) {
        return Double.isFinite(vector.getX())
                && Double.isFinite(vector.getY())
                && Double.isFinite(vector.getZ());
    }
}
