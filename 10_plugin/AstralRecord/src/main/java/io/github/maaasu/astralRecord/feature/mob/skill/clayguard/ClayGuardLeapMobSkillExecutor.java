package io.github.maaasu.astralRecord.feature.mob.skill.clayguard;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code mob_clay_guard_leap}: クレイガードが予告地点へ跳び、着地点周辺を打撃する着地スキルです。
 *
 * <p>任意パラメーターは {@code radius}（着地ダメージ半径、既定2.0、0より大きく8以下）と
 * {@code damageRatio}（攻撃力倍率、既定0.85、0より大きい）です。着地地点の地面ブロックは
 * 破壊せず、その {@link org.bukkit.block.data.BlockData} を破片パーティクルの見た目にだけ使用します。
 * ボス級の大きな着地では、予告中だけ地面の周囲へ一時的な BlockDisplay を表示します。</p>
 */
public final class ClayGuardLeapMobSkillExecutor implements MobSkillExecutor {

    public static final String SKILL_ID = "mob_clay_guard_leap";
    private static final Set<String> PARAMETER_KEYS = Set.of("radius", "damageRatio");
    private static final long LEAP_DURATION_TICKS = 14L;
    private static final double DEFAULT_RADIUS = 2.0D;
    private static final double MAX_RADIUS = 8.0D;
    private static final double DEFAULT_DAMAGE_RATIO = 0.85D;
    private static final double LEAP_HEIGHT = 1.7D;
    private static final int WARNING_RING_POINTS = 24;
    private static final int LEAP_DISPLAY_COUNT = 10;

    private final MobService mobService;
    private final DamageService damageService;
    private final ParticleDisplayService particleDisplayService;
    private final Map<UUID, LeapVisualState> activeVisuals = new HashMap<>();

    /**
     * Mob 実体制御、ダメージ、演出の依存先を指定して executor を構築します。
     *
     * @param mobService 発動中 Mob の存続確認と実体取得先
     * @param damageService 着地ダメージ適用先
     * @param particleDisplayService 着地予告と破片パーティクルの表示先
     */
    public ClayGuardLeapMobSkillExecutor(
            @NotNull MobService mobService,
            @NotNull DamageService damageService,
            @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.mobService = mobService;
        this.damageService = damageService;
        this.particleDisplayService = particleDisplayService;
    }

    @Override
    public @NotNull String id() {
        return SKILL_ID;
    }

    @Override
    public @NotNull String displayName() {
        return "跳躍着地";
    }

    @Override
    public @NotNull MobSkillTiming defaultTiming() {
        return new MobSkillTiming(7.0D, 120L, 20L);
    }

    @Override
    public void validate(@NotNull MobSkillBinding binding) {
        Map<String, Double> params = binding.params();
        if (!PARAMETER_KEYS.containsAll(params.keySet())) {
            throw new IllegalArgumentException("Unsupported parameter for " + SKILL_ID);
        }
        bounded(params.getOrDefault("radius", DEFAULT_RADIUS), "radius", 0.01D, MAX_RADIUS);
        bounded(params.getOrDefault("damageRatio", DEFAULT_DAMAGE_RATIO), "damageRatio", 0.01D, Double.MAX_VALUE);
    }

    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        Entity entity = mobService.entityController().getEntity(context.mob());
        Location landing = resolveLandingLocation(context.target().getLocation());
        if (entity == null || landing == null || entity.getWorld() != landing.getWorld()) {
            return false;
        }

        double radius = context.binding().params().getOrDefault("radius", DEFAULT_RADIUS);
        double damageRatio = context.binding().params().getOrDefault("damageRatio", DEFAULT_DAMAGE_RATIO);
        boolean largeVisual = radius >= MAX_RADIUS;
        renderLandingWarning(landing, radius, largeVisual);
        landing.getWorld().playSound(landing, Sound.BLOCK_GRAVEL_PLACE, 0.8F, 0.75F);
        LeapVisualState visual = new LeapVisualState(landing, radius, largeVisual);
        try {
            visual.spawn();
        } catch (RuntimeException ignored) {
            visual.destroy();
        }
        if (largeVisual) {
            LeapVisualState previous = activeVisuals.put(context.mob().instanceId(), visual);
            if (previous != null) {
                previous.cancel();
            }
        }
        startLeap(context.mob(), entity, landing, radius, damageRatio, visual);
        return true;
    }

    /**
     * Mob 破棄時に、その Mob が表示中の跳躍演出を回収します。
     *
     * @param mobInstanceId 破棄された Mob のインスタンス ID
     */
    public void handleMobDestroyed(@NotNull UUID mobInstanceId) {
        LeapVisualState visual = activeVisuals.remove(mobInstanceId);
        if (visual != null) {
            visual.cancel();
        }
    }

    /** Plugin 停止時に、表示中の跳躍演出をすべて回収します。 */
    public void stop() {
        for (LeapVisualState visual : List.copyOf(activeVisuals.values())) {
            visual.cancel();
        }
        activeVisuals.clear();
    }

    private void startLeap(
            @NotNull MobInstance caster,
            @NotNull Entity entity,
            @NotNull Location landing,
            double radius,
            double damageRatio,
            @NotNull LeapVisualState visual
    ) {
        Location start = entity.getLocation();
        boolean gravity = entity.hasGravity();
        entity.setGravity(false);
        entity.setVelocity(new Vector());
        start.getWorld().playSound(start, Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.7F, 1.25F);

        try {
            new BukkitRunnable() {
                private long elapsedTicks;

                @Override
                public void run() {
                    if (visual.cancelled()) {
                        cancel();
                        return;
                    }
                    MobInstance active = mobService.getInstance(caster.instanceId());
                    Entity activeEntity = active == caster ? mobService.entityController().getEntity(active) : null;
                    if (activeEntity == null || activeEntity.getWorld() != landing.getWorld()) {
                        restoreGravity(entity, gravity);
                        releaseVisual(caster.instanceId(), visual);
                        cancel();
                        return;
                    }

                    double progress = Math.min(1.0D, (double) ++elapsedTicks / LEAP_DURATION_TICKS);
                    Location position = interpolate(start, landing, progress);
                    activeEntity.teleport(position);
                    active.currentLocation(position);
                    visual.update(progress);
                    if (progress < 1.0D) {
                        return;
                    }

                    restoreGravity(activeEntity, gravity);
                    try {
                        impact(active, landing, radius, damageRatio, visual.largeVisual());
                    } finally {
                        releaseVisual(caster.instanceId(), visual);
                        cancel();
                    }
                }
            }.runTaskTimer(mobService.plugin(), 0L, 1L);
        } catch (RuntimeException exception) {
            restoreGravity(entity, gravity);
            releaseVisual(caster.instanceId(), visual);
            throw exception;
        }
    }

    private void releaseVisual(@NotNull UUID mobInstanceId, @NotNull LeapVisualState visual) {
        if (activeVisuals.get(mobInstanceId) == visual) {
            activeVisuals.remove(mobInstanceId);
        }
        visual.destroy();
    }

    private @NotNull Location interpolate(@NotNull Location start, @NotNull Location landing, double progress) {
        double x = start.getX() + (landing.getX() - start.getX()) * progress;
        double y = start.getY() + (landing.getY() - start.getY()) * progress
                + LEAP_HEIGHT * Math.sin(Math.PI * progress);
        double z = start.getZ() + (landing.getZ() - start.getZ()) * progress;
        return new Location(start.getWorld(), x, y, z, start.getYaw(), start.getPitch());
    }

    private void restoreGravity(@NotNull Entity entity, boolean gravity) {
        if (!entity.isDead()) {
            entity.setGravity(gravity);
        }
    }

    private void impact(
            @NotNull MobInstance caster,
            @NotNull Location landing,
            double radius,
            double damageRatio,
            boolean largeVisual
    ) {
        renderImpact(landing, largeVisual);
        World world = landing.getWorld();
        world.playSound(landing, Sound.BLOCK_STONE_BREAK, 1.15F, 0.8F);
        world.playSound(landing, Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.8F, 0.65F);
        for (Entity entity : world.getNearbyEntities(landing, radius, radius, radius)) {
            var victim = damageService.resolveEntity(entity);
            if (!victim.isPlayer() || victim.player() == null
                    || !AccountModeGuard.isGameplayPlayer(victim.player())
                    || horizontalDistanceSquared(entity.getLocation(), landing) > radius * radius) {
                continue;
            }
            damageService.attack(
                    AstEntity.mob(caster),
                    victim,
                    AttackType.MELEE,
                    List.of(new DamageComponent(DamageElement.NONE, damageRatio)),
                    DamageSource.SKILL
            );
        }
    }

    private void renderLandingWarning(@NotNull Location landing, double radius, boolean largeVisual) {
        particleDisplayService.spawnForNearbyViewers(
                landing,
                circlePoints(landing, radius, 0.08D),
                SharedParticleDefinitions.MOB_CLAY_GUARD_LANDING_RING
        );
        if (largeVisual) {
            particleDisplayService.spawnForNearbyViewers(
                    landing,
                    List.of(landing.clone().add(0.0D, 0.65D, 0.0D)),
                    SharedParticleDefinitions.MOB_ALDA_LEAP_CORE
            );
        }
    }

    private void renderImpact(@NotNull Location landing, boolean largeVisual) {
        Block ground = landing.clone().add(0.0D, -0.08D, 0.0D).getBlock();
        particleDisplayService.spawnForNearbyViewers(
                landing,
                SharedParticleDefinitions.mobImpactBlock(ground.getBlockData())
        );
        if (largeVisual) {
            particleDisplayService.spawnForNearbyViewers(
                    landing,
                    circlePoints(landing, 2.5D, 0.18D),
                    SharedParticleDefinitions.MOB_ALDA_LEAP_CORE
            );
        }
    }

    private @NotNull List<Location> circlePoints(@NotNull Location center, double radius, double height) {
        List<Location> points = new ArrayList<>(WARNING_RING_POINTS);
        for (int index = 0; index < WARNING_RING_POINTS; index++) {
            double angle = Math.PI * 2.0D * index / WARNING_RING_POINTS;
            points.add(center.clone().add(Math.cos(angle) * radius, height, Math.sin(angle) * radius));
        }
        return points;
    }

    private Location resolveLandingLocation(@NotNull Location target) {
        World world = target.getWorld();
        int x = target.getBlockX();
        int z = target.getBlockZ();
        int minimumY = Math.max(world.getMinHeight(), target.getBlockY() - 6);
        for (int y = target.getBlockY(); y >= minimumY; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable()) {
                return new Location(world, x + 0.5D, y + 1.0D, z + 0.5D);
            }
        }
        return null;
    }

    private double horizontalDistanceSquared(@NotNull Location first, @NotNull Location second) {
        double x = first.getX() - second.getX();
        double z = first.getZ() - second.getZ();
        return x * x + z * z;
    }

    private void bounded(double value, @NotNull String key, double minimum, double maximum) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
    }

    /** 着地予告の周囲で回転し、着地時に破棄する一時表示を管理します。 */
    private static final class LeapVisualState {
        private final Location center;
        private final double radius;
        private final boolean largeVisual;
        private final List<BlockDisplay> displays = new ArrayList<>();
        private boolean cancelled;

        private LeapVisualState(@NotNull Location center, double radius, boolean largeVisual) {
            this.center = center.clone();
            this.radius = radius;
            this.largeVisual = largeVisual;
        }

        private void spawn() {
            if (!largeVisual) {
                return;
            }
            World world = center.getWorld();
            if (world == null) {
                return;
            }
            for (int index = 0; index < LEAP_DISPLAY_COUNT; index++) {
                BlockDisplay display = world.spawn(center, BlockDisplay.class, entity -> {
                    entity.setPersistent(false);
                    entity.setInvulnerable(true);
                    entity.setGravity(false);
                    entity.setBlock(Material.DEEPSLATE_BRICKS.createBlockData());
                    entity.setBrightness(new Display.Brightness(15, 15));
                    entity.setViewRange(48.0F);
                    entity.setDisplayWidth(1.0F);
                    entity.setDisplayHeight(1.0F);
                    entity.setTeleportDuration(1);
                    entity.setInterpolationDuration(2);
                });
                displays.add(display);
            }
            update(0.0D);
        }

        private void update(double progress) {
            for (int index = 0; index < displays.size(); index++) {
                BlockDisplay display = displays.get(index);
                if (!display.isValid()) {
                    continue;
                }
                double angle = index * Math.PI * 2.0D / LEAP_DISPLAY_COUNT + progress * Math.PI * 1.8D;
                double orbitRadius = Math.max(0.9D, radius * (0.75D - progress * 0.25D));
                double height = 0.25D + 0.75D * Math.sin(Math.PI * progress)
                        + (index % 3) * 0.16D;
                display.teleport(center.clone().add(
                        Math.cos(angle) * orbitRadius,
                        height,
                        Math.sin(angle) * orbitRadius
                ));
                float scale = 0.24F + (index % 3) * 0.05F + (float) progress * 0.08F;
                display.setTransformation(new Transformation(
                        new Vector3f(-scale / 2.0F, -scale / 2.0F, -scale / 2.0F),
                        new Quaternionf().rotateXYZ(
                                (float) (progress * Math.PI + index),
                                (float) angle,
                                (float) (progress * Math.PI * 1.5D)
                        ),
                        new Vector3f(scale, scale, scale),
                        new Quaternionf()
                ));
            }
        }

        private void destroy() {
            displays.stream().filter(Entity::isValid).forEach(Entity::remove);
            displays.clear();
        }

        private void cancel() {
            cancelled = true;
            destroy();
        }

        private boolean cancelled() {
            return cancelled;
        }

        private boolean largeVisual() {
            return largeVisual;
        }
    }
}
