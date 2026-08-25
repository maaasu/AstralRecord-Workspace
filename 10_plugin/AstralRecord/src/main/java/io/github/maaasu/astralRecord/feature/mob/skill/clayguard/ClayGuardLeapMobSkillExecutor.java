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
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code mob_clay_guard_leap}: クレイガードが予告地点へ跳び、半径約2mを打撃する着地スキルです。
 *
 * <p>任意パラメーターは {@code radius}（着地ダメージ半径、既定2.0、0より大きく3以下）と
 * {@code damageRatio}（攻撃力倍率、既定0.85、0より大きい）です。着地地点の地面ブロックは
 * 破壊せず、その {@link org.bukkit.block.data.BlockData} を破片パーティクルの見た目にだけ使用します。</p>
 */
public final class ClayGuardLeapMobSkillExecutor implements MobSkillExecutor {

    public static final String SKILL_ID = "mob_clay_guard_leap";
    private static final Set<String> PARAMETER_KEYS = Set.of("radius", "damageRatio");
    private static final long LEAP_DURATION_TICKS = 14L;
    private static final double DEFAULT_RADIUS = 2.0D;
    private static final double DEFAULT_DAMAGE_RATIO = 0.85D;
    private static final double LEAP_HEIGHT = 1.7D;
    private static final int WARNING_RING_POINTS = 24;

    private final MobService mobService;
    private final DamageService damageService;
    private final ParticleDisplayService particleDisplayService;

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
        bounded(params.getOrDefault("radius", DEFAULT_RADIUS), "radius", 0.01D, 3.0D);
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
        renderLandingWarning(landing, radius);
        landing.getWorld().playSound(landing, Sound.BLOCK_GRAVEL_PLACE, 0.8F, 0.75F);
        startLeap(context.mob(), entity, landing, radius, damageRatio);
        return true;
    }

    private void startLeap(
            @NotNull MobInstance caster,
            @NotNull Entity entity,
            @NotNull Location landing,
            double radius,
            double damageRatio
    ) {
        Location start = entity.getLocation();
        boolean gravity = entity.hasGravity();
        entity.setGravity(false);
        entity.setVelocity(new Vector());
        start.getWorld().playSound(start, Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.7F, 1.25F);

        new BukkitRunnable() {
            private long elapsedTicks;

            @Override
            public void run() {
                MobInstance active = mobService.getInstance(caster.instanceId());
                Entity activeEntity = active == caster ? mobService.entityController().getEntity(active) : null;
                if (activeEntity == null || activeEntity.getWorld() != landing.getWorld()) {
                    restoreGravity(entity, gravity);
                    cancel();
                    return;
                }

                double progress = Math.min(1.0D, (double) ++elapsedTicks / LEAP_DURATION_TICKS);
                Location position = interpolate(start, landing, progress);
                activeEntity.teleport(position);
                active.currentLocation(position);
                if (progress < 1.0D) {
                    return;
                }

                restoreGravity(activeEntity, gravity);
                impact(active, landing, radius, damageRatio);
                cancel();
            }
        }.runTaskTimer(mobService.plugin(), 0L, 1L);
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

    private void impact(@NotNull MobInstance caster, @NotNull Location landing, double radius, double damageRatio) {
        renderImpact(landing);
        World world = landing.getWorld();
        world.playSound(landing, Sound.BLOCK_STONE_BREAK, 1.15F, 0.8F);
        world.playSound(landing, Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.8F, 0.65F);
        for (Entity entity : world.getNearbyEntities(landing, radius, radius, radius)) {
            var victim = damageService.resolveEntity(entity);
            if (!victim.isPlayer() || horizontalDistanceSquared(entity.getLocation(), landing) > radius * radius) {
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

    private void renderLandingWarning(@NotNull Location landing, double radius) {
        particleDisplayService.spawnForNearbyViewers(
                landing,
                circlePoints(landing, radius, 0.08D),
                SharedParticleDefinitions.MOB_CLAY_GUARD_LANDING_RING
        );
    }

    private void renderImpact(@NotNull Location landing) {
        Block ground = landing.clone().add(0.0D, -0.08D, 0.0D).getBlock();
        particleDisplayService.spawnForNearbyViewers(
                landing,
                SharedParticleDefinitions.mobImpactBlock(ground.getBlockData())
        );
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
}
