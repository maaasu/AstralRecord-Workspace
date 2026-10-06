package io.github.maaasu.astralRecord.feature.fishing.service;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/** 逃走中の実魚を、実体の当たり判定で床・壁・水に沿って一 tick ずつ動かします。 */
public final class FishingEscapeMotion {
    private static final double GRAVITY = 0.08D;
    private static final double TERMINAL_SPEED = 0.75D;
    private static final double MAX_SUBSTEP = 0.04D;
    private static final int MAX_SUBSTEPS = 32;
    private static final double CONTACT_EPSILON = 1.0E-5D;
    private static final double GROUND_PROBE = 0.025D;
    private static final double HEADING_SPREAD = Math.toRadians(55.0D);
    private static final double WALL_PROBE = 0.6D;
    private static final double MAX_RECOVERY_DISTANCE = 1.0D;
    private static final int MAX_HEADING_PROBES = 64;

    private final World world;
    private final double hopSpeed;
    private final double fleeSpeed;
    private final Location target;
    private final BoundingBox localBounds;
    private final RandomGenerator random;
    private double verticalSpeed;
    private double horizontalX;
    private double horizontalZ;
    private int detourHops;
    private boolean detouring;
    private boolean escaped;

    /**
     * 魚の足元位置、元の水中位置、足元位置を原点とした魚の当たり判定を保持します。
     *
     * @param start 逃走開始時の実魚の足元位置。world が必要です
     * @param originalWater 元の釣り上げ位置。start と同じ world が必要です
     * @param localBounds 魚の足元位置からの相対 AABB。毎 tick の衝突判定に使用します
     * @param fleeSpeed マスタで定義した陸上逃走速度（0より大きく1以下）
     * @param hopSpeed マスタで定義した跳躍速度（0より大きく1以下）
     * @throws NullPointerException 引数または world が null の場合
     * @throws IllegalArgumentException world が異なる、または AABB が不正な場合
     */
    public FishingEscapeMotion(Location start, Location originalWater, BoundingBox localBounds,
                               double fleeSpeed, double hopSpeed) {
        this(start, originalWater, localBounds, fleeSpeed, hopSpeed, ThreadLocalRandom.current());
    }

    /** 乱数源を指定し、跳躍ごとの高さと進路を再現可能に初期化します。引数の制約は公開コンストラクタと同じです。 */
    FishingEscapeMotion(Location start, Location originalWater, BoundingBox localBounds,
                        double fleeSpeed, double hopSpeed, RandomGenerator random) {
        if (!Double.isFinite(fleeSpeed) || fleeSpeed <= 0 || fleeSpeed > 1
            || !Double.isFinite(hopSpeed) || hopSpeed <= 0 || hopSpeed > 1) {
            throw new IllegalArgumentException("Invalid fish escape speeds");
        }
        this.fleeSpeed = fleeSpeed;
        this.hopSpeed = hopSpeed;
        this.random = Objects.requireNonNull(random, "random");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(originalWater, "originalWater");
        this.world = Objects.requireNonNull(start.getWorld(), "start.world");
        if (originalWater.getWorld() != world) {
            throw new IllegalArgumentException("The original water must be in the fish's world");
        }
        this.localBounds = Objects.requireNonNull(localBounds, "localBounds").clone();
        if (!finite(start) || !finite(originalWater) || !finite(this.localBounds)
                || this.localBounds.getWidthX() <= 0.0D || this.localBounds.getHeight() <= 0.0D
                || this.localBounds.getWidthZ() <= 0.0D) {
            throw new IllegalArgumentException("The fish location and bounds must be finite and nonempty");
        }
        this.target = originalWater.clone();
        this.escaped = touchesWater(start, this.localBounds);
        chooseHeading(start, fleeSpeed);
    }

    /**
     * 外部で動いた実魚の現在位置から一 tick 進めます。跳躍ごとに高さ・速度・進路を変え、壁では迂回します。
     * Bukkit API を使うためメインスレッドで呼びます。
     *
     * @param current 実魚の現在の足元位置。開始時と同じ world を使用します
     * @return 足元位置、今回の移動速度、接水、接地、跳躍開始の結果。異世界・未読込・不正座標では移動しません
     * @throws NullPointerException current が null の場合
     */
    public Step tick(Location current) {
        Objects.requireNonNull(current, "current");
        Location position = current.clone();
        if (escaped) return new Step(position, new Vector(), true, false, false);
        if (current.getWorld() != world || !finite(current) || !areaLoaded(boundsAt(current))) {
            return new Step(position, new Vector(), false, false, false);
        }
        if (touchesWater(current, localBounds)) {
            escaped = true;
            return new Step(position, new Vector(), true, false, false);
        }
        if (!canOccupy(current, localBounds)) {
            Location recovered = recoverFromSolid(current);
            if (recovered == null) return new Step(position, new Vector(), false, false, false);
            if (touchesWater(recovered, localBounds)) return waterStep(current, recovered);
            verticalSpeed = 0.0D;
            return new Step(recovered, recovered.toVector().subtract(current.toVector()),
                    false, blockedBelow(recovered), false);
        }

        boolean onGround = blockedBelow(current);
        boolean bounced = onGround && verticalSpeed <= 0.0D;
        if (bounced) {
            verticalSpeed = Math.min(1.0D, hopSpeed * random.nextDouble(0.75D, 1.25D));
            double speed = Math.min(1.0D, fleeSpeed * random.nextDouble(0.75D, 1.25D));
            if (detouring && (detourHops > 0 || !clearHeadingToWater(current))) {
                detourHops = Math.max(0, detourHops - 1);
                setHeading(horizontalX, horizontalZ, speed);
            } else {
                detouring = false;
                chooseHeading(current, speed);
            }
        }
        verticalSpeed = Math.max(-TERMINAL_SPEED, verticalSpeed - GRAVITY);
        double vx = horizontalX;
        double vz = horizontalZ;
        int steps = (int) Math.ceil(Math.max(Math.max(Math.abs(vx), Math.abs(vz)), Math.abs(verticalSpeed))
                / MAX_SUBSTEP);
        if (steps < 1 || steps > MAX_SUBSTEPS) {
            return new Step(position, new Vector(), false, onGround, false);
        }

        double sy = verticalSpeed / steps;
        boolean landed = false;
        boolean hitX = false;
        boolean hitZ = false;
        for (int index = 0; index < steps; index++) {
            // 水平方向より先に重力を解決し、壁際の床に魚を押し込まない。
            AxisMove y = moveAxis(position, Axis.Y, sy);
            if (y.unloaded()) break;
            position.setY(position.getY() + y.distance());
            if (touchesWater(position, localBounds)) return waterStep(current, position);
            if (y.blocked()) {
                if (sy < 0.0D) landed = true;
                verticalSpeed = 0.0D;
                sy = 0.0D;
            }
            AxisMove x = moveAxis(position, Axis.X, vx / steps);
            if (x.unloaded()) break;
            position.setX(position.getX() + x.distance());
            hitX |= x.blocked();
            if (touchesWater(position, localBounds)) return waterStep(current, position);
            AxisMove z = moveAxis(position, Axis.Z, vz / steps);
            if (z.unloaded()) break;
            position.setZ(position.getZ() + z.distance());
            hitZ |= z.blocked();
            if (touchesWater(position, localBounds)) return waterStep(current, position);
        }
        if (hitX || hitZ) avoidWall(position, hitX, hitZ);
        Vector displacement = position.toVector().subtract(current.toVector());
        if (displacement.lengthSquared() > CONTACT_EPSILON * CONTACT_EPSILON) {
            position.setDirection(displacement);
        }
        return new Step(position, displacement, false, landed || blockedBelow(position), bounced);
    }

    /** 水辺を基準に左右へ振った進路を選び、一回の跳躍中は水平速度を維持します。 */
    private void chooseHeading(Location position, double speed) {
        double dx = target.getX() - position.getX();
        double dz = target.getZ() - position.getZ();
        double heading = Math.hypot(dx, dz) > CONTACT_EPSILON
                ? Math.atan2(dz, dx) + random.nextDouble(-HEADING_SPREAD, HEADING_SPREAD)
                : random.nextDouble(Math.PI * 2.0D);
        setHeading(Math.cos(heading), Math.sin(heading), speed);
    }

    /** 与えた方向を指定速度へ正規化して保持します。 */
    private void setHeading(double dx, double dz, double speed) {
        double length = Math.hypot(dx, dz);
        if (length <= CONTACT_EPSILON) return;
        horizontalX = dx / length * speed;
        horizontalZ = dz / length * speed;
    }

    /**
     * 接触面から離れる方向と壁沿いの方向を実体サイズで調べ、最も動ける方向へ転じます。
     * 最低二回の跳躍と水辺への進路が開くまで迂回を維持し、壁の正面へ戻り続けることを防ぎます。
     */
    private void avoidWall(Location position, boolean hitX, boolean hitZ) {
        double speed = Math.hypot(horizontalX, horizontalZ);
        double nx = hitX ? -Math.signum(horizontalX) : 0.0D;
        double nz = hitZ ? -Math.signum(horizontalZ) : 0.0D;
        double length = Math.hypot(nx, nz);
        if (length <= CONTACT_EPSILON) return;
        nx /= length;
        nz /= length;
        double side = random.nextBoolean() ? 1.0D : -1.0D;
        double tx = -nz * side;
        double tz = nx * side;
        double[][] candidates = {
            {nx * 0.35D + tx, nz * 0.35D + tz},
            {nx * 0.35D - tx, nz * 0.35D - tz},
            {nx, nz}
        };
        double bestScore = -1.0D;
        double bestX = nx;
        double bestZ = nz;
        Vector towardWater = target.toVector().subtract(position.toVector()).setY(0.0D);
        if (towardWater.lengthSquared() > CONTACT_EPSILON * CONTACT_EPSILON) towardWater.normalize();
        for (double[] candidate : candidates) {
            double scale = WALL_PROBE / Math.hypot(candidate[0], candidate[1]);
            AxisMove x = moveAxis(position, Axis.X, candidate[0] * scale);
            if (x.unloaded()) continue;
            Location probe = position.clone().add(x.distance(), 0.0D, 0.0D);
            AxisMove z = moveAxis(probe, Axis.Z, candidate[1] * scale);
            if (z.unloaded()) continue;
            double score = x.distance() * x.distance() + z.distance() * z.distance()
                    + 0.01D * (x.distance() * towardWater.getX() + z.distance() * towardWater.getZ());
            if (score > bestScore + CONTACT_EPSILON) {
                bestScore = score;
                bestX = candidate[0];
                bestZ = candidate[1];
            }
        }
        setHeading(bestX, bestZ, speed);
        detourHops = 2;
        detouring = true;
    }

    /** 水辺へ戻す水平進路を最大16ブロック・64地点まで実体サイズで検査します。 */
    private boolean clearHeadingToWater(Location current) {
        Vector travel = target.toVector().subtract(current.toVector()).setY(0.0D);
        double distance = travel.length();
        if (distance <= CONTACT_EPSILON) return true;
        double checked = Math.min(distance, MAX_HEADING_PROBES * 0.25D);
        int steps = Math.max(1, (int) Math.ceil(checked / 0.25D));
        travel.multiply(checked / distance);
        for (int index = 1; index <= steps; index++) {
            Location probe = current.clone().add(travel.clone().multiply(index / (double) steps));
            if (!canOccupy(probe, localBounds)) return false;
        }
        return true;
    }

    /**
     * 固体へ重なった開始位置から、1ブロック以内の最も近い非衝突位置へ押し出します。
     * 他の固体・未読込チャンクへは移さず、安全な候補がなければ現在位置を維持します。
     */
    private Location recoverFromSolid(Location current) {
        BoundingBox body = boundsAt(current);
        List<BoundingBox> intersections = solidIntersections(world, body);
        Location nearest = null;
        double bestDistance = MAX_RECOVERY_DISTANCE + CONTACT_EPSILON;
        for (BoundingBox obstacle : intersections) {
            double[][] offsets = {
                {obstacle.getMinX() - body.getMaxX() - CONTACT_EPSILON, 0, 0},
                {obstacle.getMaxX() - body.getMinX() + CONTACT_EPSILON, 0, 0},
                {0, obstacle.getMinY() - body.getMaxY() - CONTACT_EPSILON, 0},
                {0, obstacle.getMaxY() - body.getMinY() + CONTACT_EPSILON, 0},
                {0, 0, obstacle.getMinZ() - body.getMaxZ() - CONTACT_EPSILON},
                {0, 0, obstacle.getMaxZ() - body.getMinZ() + CONTACT_EPSILON}
            };
            for (double[] offset : offsets) {
                double distance = Math.abs(offset[0]) + Math.abs(offset[1]) + Math.abs(offset[2]);
                if (distance >= bestDistance || distance > MAX_RECOVERY_DISTANCE) continue;
                Location candidate = current.clone().add(offset[0], offset[1], offset[2]);
                if (!canOccupy(candidate, localBounds)) continue;
                BoundingBox swept = body.clone().expandDirectional(offset[0], offset[1], offset[2]);
                if (!areaLoaded(swept) || solidIntersections(world, swept).stream()
                        .anyMatch(collision -> !intersections.contains(collision))) continue;
                nearest = candidate;
                bestDistance = distance;
            }
        }
        return nearest;
    }

    /**
     * 魚の実体サイズで固体との重なりを検査します。接触面の丸め誤差は許容します。
     * @param position 魚の足元位置
     * @param localBounds 足元からの相対AABB
     * @return 同worldの読込済み範囲内で固体と重ならない場合true
     */
    static boolean canOccupy(Location position, BoundingBox localBounds) {
        World world = position.getWorld();
        if (world == null || !finite(position) || !finite(localBounds)) return false;
        BoundingBox body = localBounds.clone().shift(position.toVector());
        return areaLoaded(world, body) && solidIntersections(world, body).isEmpty();
    }

    /** @return 読込済みの実体AABBと正の体積で重なるブロック衝突形状 */
    private static List<BoundingBox> solidIntersections(World world, BoundingBox body) {
        List<BoundingBox> collisions = new java.util.ArrayList<>();
        for (int x = (int) Math.floor(body.getMinX()); x <= (int) Math.floor(Math.nextDown(body.getMaxX())); x++) {
            // 柵・壁は起点ブロックの上へ衝突形状が張り出すため、一段下から調べます。
            for (int y = Math.max(world.getMinHeight(), (int) Math.floor(body.getMinY()) - 1);
                 y <= (int) Math.floor(Math.nextDown(body.getMaxY())); y++) {
                for (int z = (int) Math.floor(body.getMinZ()); z <= (int) Math.floor(Math.nextDown(body.getMaxZ())); z++) {
                    for (BoundingBox local : world.getBlockAt(x, y, z).getCollisionShape().getBoundingBoxes()) {
                        BoundingBox obstacle = local.clone().shift(x, y, z);
                        if (body.getMinX() < obstacle.getMaxX() - CONTACT_EPSILON
                                && body.getMaxX() > obstacle.getMinX() + CONTACT_EPSILON
                                && overlapsOtherAxes(body, obstacle, Axis.X)) collisions.add(obstacle);
                    }
                }
            }
        }
        return collisions;
    }

    /**
     * 魚の AABB が読込済みブロック内の水の実体積に触れるか調べます。
     *
     * @param position 実魚の足元位置
     * @param localBounds 足元位置を原点とした魚の相対 AABB
     * @return 水・泡柱・水草・waterlogged の液体部分に接した場合 true
     * @throws NullPointerException 引数が null の場合
     */
    public static boolean touchesWater(Location position, BoundingBox localBounds) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(localBounds, "localBounds");
        World world = position.getWorld();
        if (world == null || !finite(position) || !finite(localBounds)) return false;
        BoundingBox body = localBounds.clone().shift(position.getX(), position.getY(), position.getZ());
        int minX = (int) Math.floor(body.getMinX());
        int maxX = (int) Math.floor(Math.nextDown(body.getMaxX()));
        int minY = Math.max(world.getMinHeight(), (int) Math.floor(body.getMinY()));
        int maxY = Math.min(world.getMaxHeight() - 1, (int) Math.floor(Math.nextDown(body.getMaxY())));
        int minZ = (int) Math.floor(body.getMinZ());
        int maxZ = (int) Math.floor(Math.nextDown(body.getMaxZ()));
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int y = minY; y <= maxY; y++) {
                Block block = world.getBlockAt(x, y, z);
                double height = waterHeight(block);
                if (height <= 0.0D) continue;
                BoundingBox fluid = new BoundingBox(x, y, z, x + 1.0D, y + height, z + 1.0D);
                BoundingBox overlap = intersection(body, fluid);
                if (overlap == null) continue;
                // waterlogged ブロックでは固体部分だけへの接触を水として扱わない。
                if (!(block.getBlockData() instanceof Waterlogged)
                        || hasUnoccupiedFluid(overlap, block, x, y, z)) return true;
            }
        }
        return false;
    }

    /** @return 接水時点の移動結果 */
    private Step waterStep(Location start, Location position) {
        escaped = true;
        return new Step(position, position.toVector().subtract(start.toVector()), true, false, false);
    }

    /** @return 足元の薄い掃引に床がある場合 true */
    private boolean blockedBelow(Location position) {
        AxisMove probe = moveAxis(position, Axis.Y, -GROUND_PROBE);
        return !probe.unloaded() && probe.blocked();
    }

    /** 指定軸の swept AABB をブロックの collision shape に対して切り詰めます。 */
    private AxisMove moveAxis(Location position, Axis axis, double wanted) {
        if (Math.abs(wanted) <= CONTACT_EPSILON) return new AxisMove(0.0D, false, false);
        BoundingBox body = boundsAt(position);
        BoundingBox swept = body.clone();
        switch (axis) {
            case X -> swept.expandDirectional(wanted, 0.0D, 0.0D);
            case Y -> swept.expandDirectional(0.0D, wanted, 0.0D);
            case Z -> swept.expandDirectional(0.0D, 0.0D, wanted);
        }
        if (!areaLoaded(swept)) return new AxisMove(0.0D, false, true);
        double allowed = wanted;
        int minX = (int) Math.floor(swept.getMinX());
        int maxX = (int) Math.floor(swept.getMaxX());
        int minY = Math.max(world.getMinHeight(), (int) Math.floor(swept.getMinY()) - 1);
        int maxY = Math.min(world.getMaxHeight() - 1, (int) Math.floor(swept.getMaxY()));
        int minZ = (int) Math.floor(swept.getMinZ());
        int maxZ = (int) Math.floor(swept.getMaxZ());
        for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                Block block = world.getBlockAt(x, y, z);
                for (BoundingBox local : block.getCollisionShape().getBoundingBoxes()) {
                    BoundingBox obstacle = local.clone().shift(x, y, z);
                    if (!overlapsOtherAxes(body, obstacle, axis)) continue;
                    double leading = axis.max(body);
                    double trailing = axis.min(body);
                    double near = axis.min(obstacle);
                    double far = axis.max(obstacle);
                    if (wanted > 0.0D && leading <= near + CONTACT_EPSILON) {
                        allowed = Math.min(allowed, Math.max(0.0D, near - leading));
                    } else if (wanted < 0.0D && trailing >= far - CONTACT_EPSILON) {
                        allowed = Math.max(allowed, Math.min(0.0D, far - trailing));
                    } else if (trailing < far - CONTACT_EPSILON && leading > near + CONTACT_EPSILON) {
                        // 外部テレポートで固体へ入った場合、さらに深く進ませない。
                        allowed = 0.0D;
                    }
                }
            }
        }
        return new AxisMove(allowed, Math.abs(allowed - wanted) > CONTACT_EPSILON, false);
    }

    /** @return AABB が跨ぐ全チャンクが読込済みか */
    private boolean areaLoaded(BoundingBox box) {
        return areaLoaded(world, box);
    }

    /** @return 指定worldで実体AABBの高さと全チャンクが利用可能か */
    private static boolean areaLoaded(World world, BoundingBox box) {
        int minX = (int) Math.floor(box.getMinX()) >> 4;
        int maxX = (int) Math.floor(box.getMaxX()) >> 4;
        int minZ = (int) Math.floor(box.getMinZ()) >> 4;
        int maxZ = (int) Math.floor(box.getMaxZ()) >> 4;
        if (box.getMinY() < world.getMinHeight() || box.getMaxY() > world.getMaxHeight()) return false;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            if (!world.isChunkLoaded(x, z)) return false;
        }
        return true;
    }

    /** @return 相対 AABB を現在の足元位置へ平行移動したもの */
    private BoundingBox boundsAt(Location position) {
        return localBounds.clone().shift(position.getX(), position.getY(), position.getZ());
    }

    /** @return ブロックの水面高。水草と waterlogged は水で満たされた体積とします */
    private static double waterHeight(Block block) {
        Material type = block.getType();
        BlockData data = block.getBlockData();
        boolean source = type == Material.WATER || type == Material.BUBBLE_COLUMN
                || type == Material.SEAGRASS || type == Material.TALL_SEAGRASS
                || type == Material.KELP || type == Material.KELP_PLANT
                || data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
        if (!source) return 0.0D;
        if (block.getY() + 1 < block.getWorld().getMaxHeight()) {
            Block above = block.getRelative(0, 1, 0);
            Material aboveType = above.getType();
            if (aboveType == Material.WATER || aboveType == Material.BUBBLE_COLUMN
                    || aboveType == Material.SEAGRASS || aboveType == Material.TALL_SEAGRASS
                    || aboveType == Material.KELP || aboveType == Material.KELP_PLANT
                    || above.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
                return 1.0D;
            }
        }
        if (data instanceof Levelled levelled) {
            int level = levelled.getLevel();
            return level >= 8 ? 8.0D / 9.0D : (8.0D - level) / 9.0D;
        }
        return 8.0D / 9.0D;
    }

    /** @return 水との交差のうち、固体形状に占められない部分があるか */
    private static boolean hasUnoccupiedFluid(BoundingBox fluid, Block block, int x, int y, int z) {
        List<BoundingBox> pieces = List.of(fluid);
        for (BoundingBox local : block.getCollisionShape().getBoundingBoxes()) {
            BoundingBox solid = local.clone().shift(x, y, z);
            List<BoundingBox> remaining = new java.util.ArrayList<>();
            for (BoundingBox piece : pieces) subtract(piece, solid, remaining);
            pieces = remaining;
            if (pieces.isEmpty()) return false;
        }
        return !pieces.isEmpty();
    }

    /** AABB から別の AABB を除き、残る直方体を最大六つに分割します。 */
    private static void subtract(BoundingBox source, BoundingBox cutter, List<BoundingBox> output) {
        BoundingBox overlap = intersection(source, cutter);
        if (overlap == null) { output.add(source); return; }
        addBox(output, source.getMinX(), source.getMinY(), source.getMinZ(),
                overlap.getMinX(), source.getMaxY(), source.getMaxZ());
        addBox(output, overlap.getMaxX(), source.getMinY(), source.getMinZ(),
                source.getMaxX(), source.getMaxY(), source.getMaxZ());
        addBox(output, overlap.getMinX(), source.getMinY(), source.getMinZ(),
                overlap.getMaxX(), overlap.getMinY(), source.getMaxZ());
        addBox(output, overlap.getMinX(), overlap.getMaxY(), source.getMinZ(),
                overlap.getMaxX(), source.getMaxY(), source.getMaxZ());
        addBox(output, overlap.getMinX(), overlap.getMinY(), source.getMinZ(),
                overlap.getMaxX(), overlap.getMaxY(), overlap.getMinZ());
        addBox(output, overlap.getMinX(), overlap.getMinY(), overlap.getMaxZ(),
                overlap.getMaxX(), overlap.getMaxY(), source.getMaxZ());
    }

    /** @return 正の体積で重なる領域。面で触れるだけなら null */
    private static BoundingBox intersection(BoundingBox a, BoundingBox b) {
        double minX = Math.max(a.getMinX(), b.getMinX());
        double minY = Math.max(a.getMinY(), b.getMinY());
        double minZ = Math.max(a.getMinZ(), b.getMinZ());
        double maxX = Math.min(a.getMaxX(), b.getMaxX());
        double maxY = Math.min(a.getMaxY(), b.getMaxY());
        double maxZ = Math.min(a.getMaxZ(), b.getMaxZ());
        return minX < maxX && minY < maxY && minZ < maxZ
                ? new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ) : null;
    }

    /** 正の体積を持つ領域だけ追加します。 */
    private static void addBox(List<BoundingBox> output, double minX, double minY, double minZ,
                               double maxX, double maxY, double maxZ) {
        if (minX < maxX && minY < maxY && minZ < maxZ) {
            output.add(new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ));
        }
    }

    /** @return 移動軸以外の二軸で体積が重なるか */
    private static boolean overlapsOtherAxes(BoundingBox body, BoundingBox obstacle, Axis axis) {
        // 接触面の丸め誤差を重なりとみなすと、着地しただけで水平移動まで停止します。
        return (axis == Axis.X || body.getMinX() < obstacle.getMaxX() - CONTACT_EPSILON
                && body.getMaxX() > obstacle.getMinX() + CONTACT_EPSILON)
                && (axis == Axis.Y || body.getMinY() < obstacle.getMaxY() - CONTACT_EPSILON
                && body.getMaxY() > obstacle.getMinY() + CONTACT_EPSILON)
                && (axis == Axis.Z || body.getMinZ() < obstacle.getMaxZ() - CONTACT_EPSILON
                && body.getMaxZ() > obstacle.getMinZ() + CONTACT_EPSILON);
    }

    /** @return Location の三座標が有限か */
    private static boolean finite(Location location) {
        return Double.isFinite(location.getX()) && Double.isFinite(location.getY())
                && Double.isFinite(location.getZ());
    }

    /** @return AABB の全端点が有限か */
    private static boolean finite(BoundingBox box) {
        return Double.isFinite(box.getMinX()) && Double.isFinite(box.getMinY())
                && Double.isFinite(box.getMinZ()) && Double.isFinite(box.getMaxX())
                && Double.isFinite(box.getMaxY()) && Double.isFinite(box.getMaxZ());
    }

    private enum Axis {
        X { double min(BoundingBox box) { return box.getMinX(); } double max(BoundingBox box) { return box.getMaxX(); } },
        Y { double min(BoundingBox box) { return box.getMinY(); } double max(BoundingBox box) { return box.getMaxY(); } },
        Z { double min(BoundingBox box) { return box.getMinZ(); } double max(BoundingBox box) { return box.getMaxZ(); } };

        abstract double min(BoundingBox box);
        abstract double max(BoundingBox box);
    }

    private record AxisMove(double distance, boolean blocked, boolean unloaded) { }

    /** 魚へ適用する足元位置と今回の変位、逃走完了・床接地・跳躍開始の結果です。 */
    public record Step(Location position, Vector velocity, boolean escaped, boolean grounded, boolean bounced) { }
}
