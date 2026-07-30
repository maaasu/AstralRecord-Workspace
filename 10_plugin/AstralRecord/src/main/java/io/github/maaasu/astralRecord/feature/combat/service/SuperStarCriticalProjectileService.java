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
import java.util.concurrent.ThreadLocalRandom;

/**
 * 超星会心で生成するネザースター追尾弾の表示・移動・命中を管理します。
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
    static final double HOMING_SPEED_PER_TICK = 0.35D;
    static final double TARGET_RADIUS = 24.0D;
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
     * 指定位置から7個の超星会心追尾弾を生成します。
     *
     * @param attacker 発生元プレイヤー
     * @param origin 被弾 Mob を基準にした生成位置
     * @param damageApplier 命中時に再計算ダメージを適用する処理
     */
    public void spawn(
            @NotNull AstEntity attacker,
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
            int initialTicks = random.nextInt(MIN_INITIAL_TICKS, MAX_INITIAL_TICKS + 1);
            ItemDisplay display = world.spawn(origin, ItemDisplay.class, this::configureDisplay);
            projectiles.add(new ProjectileState(
                    attacker,
                    display,
                    origin.clone(),
                    direction.multiply(initialSpeed),
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
            return projectile.move(projectile.location.clone().add(projectile.velocity));
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
        double distance = offset.length();
        Vector movement = distance <= HOMING_SPEED_PER_TICK
                ? offset
                : offset.normalize().multiply(HOMING_SPEED_PER_TICK);
        Location next = projectile.location.clone().add(movement);
        if (intersects(targetBounds(target), projectile.location, next)) {
            projectile.removeDisplay();
            particleDisplayService.spawnForNearbyViewers(targetCenter, SharedParticleDefinitions.SUPER_STAR_CRITICAL_IMPACT);
            projectile.damageApplier.apply(AstEntity.mob(target));
            return false;
        }
        return projectile.move(next);
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
                .filter(mob -> mob.state() != MobState.DEAD)
                .filter(mob -> mob.currentHealth() > 0.0D)
                .filter(mob -> mob.template().category() != MobCategory.NPC)
                .filter(mob -> !mob.template().damageImmune())
                .filter(mob -> mob.currentLocation().getWorld() == world)
                .filter(mob -> targetCenter(mob).distanceSquared(origin) <= radiusSquared)
                .min(Comparator
                        .comparingDouble((MobInstance mob) -> targetCenter(mob).distanceSquared(origin))
                        .thenComparing(mob -> mob.instanceId().toString()))
                .orElse(null);
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
     * 1 tick の移動線分が Mob の当たり判定へ接触するか判定します。
     *
     * @param targetBounds Mob の当たり判定
     * @param from 移動始点
     * @param to 移動終点
     * @return 接触する場合は {@code true}
     */
    private static boolean intersects(
            @NotNull BoundingBox targetBounds,
            @NotNull Location from,
            @NotNull Location to
    ) {
        BoundingBox expanded = targetBounds.clone().expand(COLLISION_RADIUS);
        Vector origin = from.toVector();
        Vector destination = to.toVector();
        if (expanded.contains(origin) || expanded.contains(destination)) {
            return true;
        }
        Vector movement = destination.subtract(origin);
        double distance = movement.length();
        if (distance <= 1.0E-8D) {
            return false;
        }
        RayTraceResult hit = expanded.rayTrace(origin, movement.normalize(), distance);
        return hit != null;
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
        private final ItemDisplay display;
        private final Vector velocity;
        private final int initialTicks;
        private final ProjectileDamageApplier damageApplier;
        private Location location;
        private int ageTicks;

        private ProjectileState(
                AstEntity attacker,
                ItemDisplay display,
                Location location,
                Vector velocity,
                int initialTicks,
                ProjectileDamageApplier damageApplier
        ) {
            this.attacker = attacker;
            this.display = display;
            this.location = location;
            this.velocity = velocity;
            this.initialTicks = initialTicks;
            this.damageApplier = damageApplier;
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
