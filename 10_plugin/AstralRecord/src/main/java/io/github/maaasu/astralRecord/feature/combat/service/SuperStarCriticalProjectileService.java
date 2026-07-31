package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 超星会心で生成するネザースター追尾弾の表示・曲線移動・命中を管理します。
 */
public final class SuperStarCriticalProjectileService {

    static final int PROJECTILE_COUNT = 7;
    static final int MIN_INITIAL_TICKS = 10;
    static final int MAX_INITIAL_TICKS = 20;
    static final int LIFETIME_TICKS = 100;
    static final double MIN_INITIAL_SPEED_PER_TICK = 0.16D;
    static final double MAX_INITIAL_SPEED_PER_TICK = 0.24D;
    static final double MIN_INITIAL_ELEVATION_RADIANS = Math.toRadians(25.0D);
    static final double MAX_INITIAL_ELEVATION_RADIANS = Math.toRadians(65.0D);
    static final double INITIAL_CURVE_RADIANS_PER_TICK = Math.toRadians(3.0D);
    static final double HOMING_SPEED_PER_TICK = 0.35D;
    static final double HOMING_MAX_TURN_RADIANS_PER_TICK = Math.toRadians(15.0D);
    static final double HOMING_CURVE_STRENGTH = 0.30D;
    static final double HOMING_CURVE_FADE_DISTANCE = 3.0D;
    static final double MIN_CURVE_PHASE_STEP = 0.25D;
    static final double MAX_CURVE_PHASE_STEP = 0.55D;
    static final double TARGET_RADIUS = 24.0D;
    private static final double VECTOR_EPSILON = 1.0E-8D;
    private static final double COLLISION_RADIUS = 0.2D;
    private static final double FALLBACK_TARGET_HALF_WIDTH = 0.45D;
    private static final double FALLBACK_TARGET_HEIGHT = 1.8D;

    private final Plugin plugin;
    private final MobService mobService;
    private final ParticleDisplayService particleDisplayService;
    private final List<ProjectileState> projectiles = new ArrayList<>();
    private BukkitTask task;

    /**
     * 追尾弾サービスを構築します。
     *
     * @param plugin スケジューラと表示エンティティの所有プラグイン
     * @param mobService 攻撃可能 Mob の取得元
     * @param particleDisplayService パーティクル表示サービス
     */
    public SuperStarCriticalProjectileService(
            @NotNull Plugin plugin,
            @NotNull MobService mobService,
            @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.plugin = plugin;
        this.mobService = mobService;
        this.particleDisplayService = particleDisplayService;
    }

    /**
     * 指定位置から7個の超星会心追尾弾を生成します。各弾はランダムな曲率軸と位相を
     * 持ち、初期飛行と対象追尾の両方で曲線軌道を描きます。
     *
     * @param attacker 発生元プレイヤー
     * @param originVictim 追尾弾の生成元となった被弾 Mob
     * @param origin 被弾 Mob を基準にした生成位置
     * @param damageApplier 命中時に再計算ダメージを適用する処理
     */
    public void spawn(
            @NotNull AstEntity attacker,
            @NotNull MobInstance originVictim,
            @NotNull Location origin,
            @NotNull ProjectileDamageApplier damageApplier
    ) {
        World world = origin.getWorld();
        if (world == null || !isActiveAttacker(attacker)) {
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = 0; index < PROJECTILE_COUNT; index++) {
            double azimuth = random.nextDouble(0.0D, Math.PI * 2.0D);
            double elevation = random.nextDouble(MIN_INITIAL_ELEVATION_RADIANS, MAX_INITIAL_ELEVATION_RADIANS);
            double initialSpeed = random.nextDouble(MIN_INITIAL_SPEED_PER_TICK, MAX_INITIAL_SPEED_PER_TICK);
            Vector direction = initialDirection(azimuth, elevation);
            Vector curveAxis = curvatureAxis(direction, random.nextDouble(0.0D, Math.PI * 2.0D));
            double curvePhase = random.nextDouble(0.0D, Math.PI * 2.0D);
            double curvePhaseStep = random.nextDouble(MIN_CURVE_PHASE_STEP, MAX_CURVE_PHASE_STEP)
                    * (random.nextBoolean() ? 1.0D : -1.0D);
            int initialTicks = random.nextInt(MIN_INITIAL_TICKS, MAX_INITIAL_TICKS + 1);
            ItemDisplay display = world.spawn(origin, ItemDisplay.class, this::configureDisplay);
            projectiles.add(new ProjectileState(
                    attacker,
                    originVictim,
                    display,
                    origin.clone(),
                    direction.multiply(initialSpeed),
                    curveAxis,
                    curvePhase,
                    curvePhaseStep,
                    initialTicks,
                    damageApplier
            ));
        }
        ensureTaskStarted();
    }

    /**
     * 実行中の追尾弾をすべて除去し、更新タスクを停止します。
     */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (ProjectileState projectile : projectiles) {
            projectile.removeDisplay();
        }
        projectiles.clear();
    }

    /**
     * 水平方位と仰角から正規化済みの初速方向を作成します。
     *
     * @param azimuth 水平面の方位角（radian）
     * @param elevation 水平面から上向きの仰角（radian）
     * @return 正規化済み方向
     */
    static @NotNull Vector initialDirection(double azimuth, double elevation) {
        double horizontal = Math.cos(elevation);
        return new Vector(
                Math.cos(azimuth) * horizontal,
                Math.sin(elevation),
                Math.sin(azimuth) * horizontal
        );
    }

    /**
     * 初速方向に直交する曲率軸を、指定角度から決定的に作成します。
     *
     * @param direction 初速方向
     * @param angle 直交面上の曲がる向き（radian）
     * @return 正規化済み曲率軸
     */
    static @NotNull Vector curvatureAxis(@NotNull Vector direction, double angle) {
        if (direction.lengthSquared() <= VECTOR_EPSILON) {
            return new Vector(0.0D, 1.0D, 0.0D);
        }
        Vector normalizedDirection = direction.clone().normalize();
        Vector reference = Math.abs(normalizedDirection.getY()) < 0.9D
                ? new Vector(0.0D, 1.0D, 0.0D)
                : new Vector(1.0D, 0.0D, 0.0D);
        Vector firstAxis = normalizedDirection.clone().crossProduct(reference).normalize();
        Vector secondAxis = normalizedDirection.clone().crossProduct(firstAxis).normalize();
        return firstAxis.multiply(Math.cos(angle))
                .add(secondAxis.multiply(Math.sin(angle)))
                .normalize();
    }

    /**
     * 現在速度を曲率軸まわりに回転し、速度を維持した曲線移動量を返します。
     *
     * @param velocity 現在速度
     * @param curveAxis 曲率軸
     * @param turnRadians 1 tick の回転角（radian）
     * @return 次 tick の移動量
     */
    static @NotNull Vector curvedVelocity(
            @NotNull Vector velocity,
            @NotNull Vector curveAxis,
            double turnRadians
    ) {
        if (velocity.lengthSquared() <= VECTOR_EPSILON || curveAxis.lengthSquared() <= VECTOR_EPSILON) {
            return velocity.clone();
        }
        return velocity.clone().rotateAroundAxis(curveAxis.clone().normalize(), turnRadians);
    }

    /**
     * 現在方向から目標方向へ、指定した最大角度まで旋回します。
     *
     * @param currentDirection 現在方向
     * @param targetDirection 目標方向
     * @param maxTurnRadians 1 tick の最大旋回角（radian）
     * @return 正規化済みの次方向
     */
    static @NotNull Vector turnTowards(
            @NotNull Vector currentDirection,
            @NotNull Vector targetDirection,
            double maxTurnRadians
    ) {
        if (targetDirection.lengthSquared() <= VECTOR_EPSILON) {
            return currentDirection.clone();
        }
        if (currentDirection.lengthSquared() <= VECTOR_EPSILON) {
            return targetDirection.clone().normalize();
        }

        Vector current = currentDirection.clone().normalize();
        Vector target = targetDirection.clone().normalize();
        double dot = Math.max(-1.0D, Math.min(1.0D, current.dot(target)));
        double angle = Math.acos(dot);
        double limitedTurn = Math.max(0.0D, Math.min(angle, maxTurnRadians));
        if (angle <= limitedTurn + VECTOR_EPSILON) {
            return target;
        }
        if (limitedTurn <= VECTOR_EPSILON) {
            return current;
        }

        Vector rotationAxis = current.clone().crossProduct(target);
        if (rotationAxis.lengthSquared() <= VECTOR_EPSILON) {
            rotationAxis = curvatureAxis(current, 0.0D);
        }
        return current.rotateAroundAxis(rotationAxis.normalize(), limitedTurn).normalize();
    }

    /**
     * 対象への進行成分へ慣性と横方向の揺らぎを合成し、曲線追尾の移動量を返します。
     * 対象直前では揺らぎを減衰させ、接触判定へ収束させます。
     *
     * @param currentVelocity 現在速度
     * @param targetOffset 現在位置から対象中央への差分
     * @param lateralSeed 追尾弾ごとに固定したランダムな横方向の種
     * @param curvePhase 揺らぎの位相
     * @return 次 tick の移動量
     */
    static @NotNull Vector curvedHomingMovement(
            @NotNull Vector currentVelocity,
            @NotNull Vector targetOffset,
            @NotNull Vector lateralSeed,
            double curvePhase
    ) {
        double distance = targetOffset.length();
        if (distance <= VECTOR_EPSILON) {
            return targetOffset.clone();
        }
        Vector desiredDirection = targetOffset.clone().normalize();
        Vector currentDirection = currentVelocity.lengthSquared() <= VECTOR_EPSILON
                ? desiredDirection.clone()
                : currentVelocity.clone().normalize();
        Vector lateral = lateralSeed.clone()
                .subtract(desiredDirection.clone().multiply(lateralSeed.dot(desiredDirection)));
        if (lateral.lengthSquared() <= VECTOR_EPSILON) {
            Vector reference = Math.abs(desiredDirection.getY()) < 0.9D
                    ? new Vector(0.0D, 1.0D, 0.0D)
                    : new Vector(1.0D, 0.0D, 0.0D);
            lateral = desiredDirection.clone().crossProduct(reference);
        }
        lateral.normalize();

        double fade = Math.min(1.0D, Math.max(0.0D,
                (distance - HOMING_SPEED_PER_TICK) / HOMING_CURVE_FADE_DISTANCE));
        double wave = 0.55D + 0.45D * Math.sin(curvePhase);
        Vector curvedDirection = desiredDirection.clone()
                .add(lateral.multiply(HOMING_CURVE_STRENGTH * fade * wave))
                .normalize();
        return turnTowards(currentDirection, curvedDirection, HOMING_MAX_TURN_RADIANS_PER_TICK)
                .multiply(Math.min(distance, HOMING_SPEED_PER_TICK));
    }

    /**
     * 追尾弾をドロップしたネザースター風の表示へ設定します。
     *
     * @param display 設定対象
     */
    private void configureDisplay(@NotNull ItemDisplay display) {
        display.setItemStack(new ItemStack(Material.NETHER_STAR));
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
        display.setBrightness(new Display.Brightness(15, 15));
        display.setGravity(false);
        display.setInvulnerable(true);
        display.setPersistent(false);
        display.setShadowRadius(0.0F);
        display.setShadowStrength(0.0F);
        display.setTeleportDuration(1);
    }

    /** 追尾弾が存在する間だけ毎 tick の更新タスクを開始します。 */
    private void ensureTaskStarted() {
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, (Runnable) this::tick, 1L, 1L);
        }
    }

    /** 全追尾弾を1 tick 更新し、同一ワールドの軌跡をまとめて表示します。 */
    private void tick() {
        Map<World, List<Location>> trailLocations = new HashMap<>();
        Iterator<ProjectileState> iterator = projectiles.iterator();
        while (iterator.hasNext()) {
            ProjectileState projectile = iterator.next();
            if (!tick(projectile)) {
                projectile.removeDisplay();
                iterator.remove();
                continue;
            }
            World world = projectile.location.getWorld();
            if (world != null) {
                trailLocations.computeIfAbsent(world, ignored -> new ArrayList<>())
                        .add(projectile.location.clone());
            }
        }
        renderTrails(trailLocations);
        if (projectiles.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * 1個の追尾弾を更新します。
     *
     * @param projectile 更新対象
     * @return 次 tick まで維持する場合は {@code true}
     */
    private boolean tick(@NotNull ProjectileState projectile) {
        if (projectile.ageTicks >= LIFETIME_TICKS
                || !projectile.display.isValid()
                || !isActiveAttacker(projectile.attacker)) {
            return false;
        }

        projectile.incrementAge();
        if (projectile.ageTicks <= projectile.initialTicks) {
            Vector movement = curvedVelocity(
                    projectile.velocity,
                    projectile.curveAxis,
                    INITIAL_CURVE_RADIANS_PER_TICK
            );
            projectile.updateVelocity(movement);
            return moveOrHit(projectile, projectile.location.clone().add(movement));
        }

        MobInstance target = nearestTarget(projectile.location);
        if (target == null) {
            return true;
        }
        Location targetCenter = targetCenter(target);
        if (targetCenter.getWorld() != projectile.location.getWorld()) {
            return true;
        }
        Vector offset = targetCenter.toVector().subtract(projectile.location.toVector());
        Vector movement = curvedHomingMovement(
                projectile.velocity,
                offset,
                projectile.curveAxis,
                projectile.curvePhase
        );
        projectile.updateVelocity(movement);
        projectile.advanceCurvePhase();
        Location next = projectile.location.clone().add(movement);
        return moveOrHit(projectile, next);
    }

    /**
     * 移動線分上で最初に接触した攻撃可能 Mob へ命中させ、未命中なら表示を移動します。
     *
     * @param projectile 移動する追尾弾
     * @param next 移動終点
     * @return 次 tick まで維持する場合は {@code true}
     */
    private boolean moveOrHit(@NotNull ProjectileState projectile, @NotNull Location next) {
        UUID excludedStartingOverlapMobId = projectile.originExitPending
                ? projectile.originVictim.instanceId()
                : null;
        MobInstance hitTarget = firstCollision(projectile.location, next, excludedStartingOverlapMobId);
        if (hitTarget == null) {
            projectile.updateOriginExit(next);
            return projectile.move(next);
        }
        projectile.removeDisplay();
        particleDisplayService.spawnForNearbyViewers(
                targetCenter(hitTarget),
                SharedParticleDefinitions.SUPER_STAR_CRITICAL_IMPACT
        );
        projectile.damageApplier.apply(AstEntity.mob(hitTarget));
        return false;
    }

    /**
     * 指定位置から索敵半径内にいる最寄りの攻撃可能 Mob を返します。
     *
     * @param origin 追尾弾の現在位置
     * @return 最寄り Mob。存在しない場合は {@code null}
     */
    private @Nullable MobInstance nearestTarget(@NotNull Location origin) {
        World world = origin.getWorld();
        if (world == null) {
            return null;
        }
        double radiusSquared = TARGET_RADIUS * TARGET_RADIUS;
        return mobService.getInstances().stream()
                .filter(mob -> isAttackableTarget(mob, world))
                .filter(mob -> targetCenter(mob).distanceSquared(origin) <= radiusSquared)
                .min(Comparator
                        .comparingDouble((MobInstance mob) -> targetCenter(mob).distanceSquared(origin))
                        .thenComparing(mob -> mob.instanceId().toString()))
                .orElse(null);
    }

    /**
     * 移動線分上で最初に接触する攻撃可能 Mob を返します。
     *
     * @param from 移動始点
     * @param to 移動終点
     * @param excludedStartingOverlapMobId 始点内でも接触から除外する生成元 Mob ID。除外しない場合は {@code null}
     * @return 最初に接触する Mob。存在しない場合は {@code null}
     */
    @Nullable MobInstance firstCollision(
            @NotNull Location from,
            @NotNull Location to,
            @Nullable UUID excludedStartingOverlapMobId
    ) {
        World world = from.getWorld();
        if (world == null || to.getWorld() != world) {
            return null;
        }

        MobInstance first = null;
        double firstDistance = Double.POSITIVE_INFINITY;
        for (MobInstance mob : mobService.getInstances()) {
            if (!isAttackableTarget(mob, world)) {
                continue;
            }
            double distance = collisionDistance(
                    targetBounds(mob),
                    from,
                    to,
                    mob.instanceId().equals(excludedStartingOverlapMobId)
            );
            if (distance < firstDistance
                    || (Double.compare(distance, firstDistance) == 0
                    && first != null
                    && mob.instanceId().toString().compareTo(first.instanceId().toString()) < 0)) {
                first = mob;
                firstDistance = distance;
            }
        }
        return first;
    }

    /**
     * 攻撃可能かつ指定ワールドに存在する Mob か判定します。
     *
     * @param mob 判定対象
     * @param world 追尾弾のワールド
     * @return 攻撃可能な場合は {@code true}
     */
    private static boolean isAttackableTarget(@NotNull MobInstance mob, @NotNull World world) {
        return mob.state() != MobState.DEAD
                && mob.currentHealth() > 0.0D
                && mob.template().category() != MobCategory.NPC
                && !mob.template().damageImmune()
                && mob.currentLocation().getWorld() == world;
    }

    /**
     * ワールド単位でまとめた追尾弾の軌跡パーティクルを表示します。
     *
     * @param trailLocations ワールド別の追尾弾位置
     */
    private void renderTrails(@NotNull Map<World, List<Location>> trailLocations) {
        for (List<Location> locations : trailLocations.values()) {
            if (locations.isEmpty()) {
                continue;
            }
            Location center = locations.getFirst();
            particleDisplayService.spawnForNearbyViewers(
                    center,
                    locations,
                    SharedParticleDefinitions.SUPER_STAR_CRITICAL_TRAIL_END_ROD
            );
            particleDisplayService.spawnForNearbyViewers(
                    center,
                    locations,
                    SharedParticleDefinitions.SUPER_STAR_CRITICAL_TRAIL_SPARK
            );
        }
    }

    /**
     * 追尾弾の所有プレイヤーが引き続き攻撃可能な状態か判定します。
     *
     * @param attacker 発生元
     * @return オンラインかつ生存中なら {@code true}
     */
    private boolean isActiveAttacker(@NotNull AstEntity attacker) {
        if (!attacker.isPlayer() || attacker.player() == null) {
            return false;
        }
        Player player = attacker.player().getBukkit();
        return player.isOnline() && !player.isDead();
    }

    /**
     * Mob の当たり判定中央を返します。
     *
     * @param mob 対象 Mob
     * @return 当たり判定中央
     */
    private static @NotNull Location targetCenter(@NotNull MobInstance mob) {
        BoundingBox bounds = targetBounds(mob);
        Vector center = bounds.getCenter();
        return new Location(mob.currentLocation().getWorld(), center.getX(), center.getY(), center.getZ());
    }

    /**
     * 実体または既定寸法から Mob の当たり判定を返します。
     *
     * @param mob 対象 Mob
     * @return 当たり判定
     */
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

    /**
     * 1 tick の移動線分が Mob の当たり判定へ入るまでの距離を返します。
     * 始点が当たり判定内にある場合は距離0とし、生成元 Mob の初期exitだけを明示的に除外できます。
     *
     * @param targetBounds Mob の当たり判定
     * @param from 移動始点
     * @param to 移動終点
     * @param excludeStartingOverlap 始点内の接触を除外する場合は {@code true}
     * @return 接触までの距離。接触しない場合は正の無限大
     */
    private static double collisionDistance(
            @NotNull BoundingBox targetBounds,
            @NotNull Location from,
            @NotNull Location to,
            boolean excludeStartingOverlap
    ) {
        BoundingBox expanded = targetBounds.clone().expand(COLLISION_RADIUS);
        Vector origin = from.toVector();
        Vector destination = to.toVector();
        if (expanded.contains(origin)) {
            return excludeStartingOverlap ? Double.POSITIVE_INFINITY : 0.0D;
        }
        Vector movement = destination.subtract(origin);
        double distance = movement.length();
        if (distance <= 1.0E-8D) {
            return Double.POSITIVE_INFINITY;
        }
        RayTraceResult hit = expanded.rayTrace(origin, movement.normalize(), distance);
        return hit == null
                ? Double.POSITIVE_INFINITY
                : hit.getHitPosition().distance(origin);
    }

    /**
     * 追尾弾命中時の再計算ダメージ適用処理です。
     */
    @FunctionalInterface
    public interface ProjectileDamageApplier {
        /**
         * 命中対象へダメージを適用します。
         *
         * @param target 命中した攻撃可能 Mob
         */
        void apply(@NotNull AstEntity target);
    }

    private static final class ProjectileState {
        private final AstEntity attacker;
        private final MobInstance originVictim;
        private final ItemDisplay display;
        private final Vector velocity;
        private final Vector curveAxis;
        private final double curvePhaseStep;
        private final int initialTicks;
        private final ProjectileDamageApplier damageApplier;
        private Location location;
        private double curvePhase;
        private int ageTicks;
        private boolean originExitPending = true;

        private ProjectileState(
                AstEntity attacker,
                MobInstance originVictim,
                ItemDisplay display,
                Location location,
                Vector velocity,
                Vector curveAxis,
                double curvePhase,
                double curvePhaseStep,
                int initialTicks,
                ProjectileDamageApplier damageApplier
        ) {
            this.attacker = attacker;
            this.originVictim = originVictim;
            this.display = display;
            this.location = location;
            this.velocity = velocity;
            this.curveAxis = curveAxis;
            this.curvePhase = curvePhase;
            this.curvePhaseStep = curvePhaseStep;
            this.initialTicks = initialTicks;
            this.damageApplier = damageApplier;
        }

        /** 次 tick の速度へ更新します。 */
        private void updateVelocity(Vector nextVelocity) {
            velocity.copy(nextVelocity);
        }

        /** 曲線追尾の位相を1 tick 進めます。 */
        private void advanceCurvePhase() {
            curvePhase += curvePhaseStep;
        }

        /** 生成元 Mob の初期当たり判定から抜けた時点で、始点内除外を終了します。 */
        private void updateOriginExit(Location next) {
            if (originExitPending && !targetBounds(originVictim).clone()
                    .expand(COLLISION_RADIUS)
                    .contains(next.toVector())) {
                originExitPending = false;
            }
        }

        /**
         * 表示と内部位置を次の位置へ移動します。
         *
         * @param next 次の位置
         * @return teleport に成功した場合は {@code true}
         */
        private boolean move(Location next) {
            location = next;
            return display.teleport(next);
        }

        /** 経過 tick を1増やします。 */
        private void incrementAge() {
            ageTicks++;
        }

        /** 有効な表示エンティティをワールドから除去します。 */
        private void removeDisplay() {
            if (display.isValid()) {
                display.remove();
            }
        }

    }
}
