package io.github.maaasu.astralRecord.feature.mob.skill.ryushel;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.skill.active.service.TemporarySkillEffectService;
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
 * {@code mob_ryushel_flower_leap}: 羊ボスが花の予兆を置いて跳び、着地後に羊毛が散る隙を作ります。
 *
 * <p>任意パラメーターは {@code radius}（着地範囲、既定2.5、0より大きく4以下）と
 * {@code damageRatio}（攻撃力倍率、既定0.75、0より大きい）です。
 * 防御力と回避率の低下は着地後80 tickだけ適用し、失敗・中断時には適用しません。</p>
 */
public final class RyushelFlowerLeapMobSkillExecutor implements MobSkillExecutor {

    public static final String SKILL_ID = "mob_ryushel_flower_leap";
    private static final String EXPOSED_EFFECT_ID = "ryushel-flower-leap-exposed";
    private static final Set<String> PARAMETER_KEYS = Set.of("radius", "damageRatio");
    private static final long LEAP_TICKS = 12L;
    private static final long EXPOSED_TICKS = 80L;
    private static final double MAX_LEAP_DISTANCE = 8.0D;
    private static final double DEFAULT_RADIUS = 2.5D;
    private static final double DEFAULT_DAMAGE_RATIO = 0.75D;
    private static final double EXPOSED_DEFENSE_MULTIPLIER = 0.60D;
    private static final double EXPOSED_EVASION_MULTIPLIER = 0.15D;
    private static final int RING_POINTS = 24;

    private final MobService mobService;
    private final DamageService damageService;
    private final TemporarySkillEffectService temporarySkillEffectService;
    private final ParticleDisplayService particleDisplayService;

    /**
     * Mob の移動、攻撃、弱点時間、演出に使うサービスを指定します。
     *
     * @param mobService Mob実体と同期タスクの所有元
     * @param damageService 着地ダメージの適用先
     * @param temporarySkillEffectService 着地後の一時防御・回避補正
     * @param particleDisplayService 予兆と羊毛が散る演出
     */
    public RyushelFlowerLeapMobSkillExecutor(
            @NotNull MobService mobService,
            @NotNull DamageService damageService,
            @NotNull TemporarySkillEffectService temporarySkillEffectService,
            @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.mobService = mobService;
        this.damageService = damageService;
        this.temporarySkillEffectService = temporarySkillEffectService;
        this.particleDisplayService = particleDisplayService;
    }

    @Override
    public @NotNull String id() {
        return SKILL_ID;
    }

    @Override
    public @NotNull String displayName() {
        return "花影の跳躍";
    }

    @Override
    public @NotNull MobSkillTiming defaultTiming() {
        return new MobSkillTiming(MAX_LEAP_DISTANCE, 120L, 20L);
    }

    @Override
    public void validate(@NotNull MobSkillBinding binding) {
        Map<String, Double> params = binding.params();
        if (!PARAMETER_KEYS.containsAll(params.keySet())) {
            throw new IllegalArgumentException("Unsupported parameter for " + SKILL_ID);
        }
        double radius = params.getOrDefault("radius", DEFAULT_RADIUS);
        double damageRatio = params.getOrDefault("damageRatio", DEFAULT_DAMAGE_RATIO);
        if (!Double.isFinite(radius) || radius <= 0.0D || radius > 4.0D
                || !Double.isFinite(damageRatio) || damageRatio <= 0.0D) {
            throw new IllegalArgumentException("Invalid flower leap parameters");
        }
    }

    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        MobInstance caster = context.mob();
        Entity entity = mobService.entityController().getEntity(caster);
        if (entity == null || entity.getWorld() != context.target().getWorld()) {
            return false;
        }
        Location landing = landingAt(context.target().getLocation());
        if (landing == null || horizontalDistanceSquared(entity.getLocation(), landing)
                > MAX_LEAP_DISTANCE * MAX_LEAP_DISTANCE) {
            return false;
        }

        double radius = context.binding().params().getOrDefault("radius", DEFAULT_RADIUS);
        double damageRatio = context.binding().params().getOrDefault("damageRatio", DEFAULT_DAMAGE_RATIO);
        particleDisplayService.spawnForNearbyViewers(
                landing, ring(landing, radius), SharedParticleDefinitions.MOB_GRANBAL_BLOOM);
        landing.getWorld().playSound(landing, Sound.ENTITY_SHEEP_AMBIENT, 1.0F, 1.3F);
        startLeap(caster, entity, landing, radius, damageRatio);
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
        boolean originalGravity = entity.hasGravity();
        caster.scriptedAction(true);
        entity.setGravity(false);
        entity.setVelocity(new Vector());
        try {
            new BukkitRunnable() {
                private long elapsedTicks;

                @Override
                public void run() {
                    MobInstance active = mobService.getInstance(caster.instanceId());
                    Entity activeEntity = active == caster ? mobService.entityController().getEntity(active) : null;
                    if (active == null || active.state() == MobState.DEAD
                            || activeEntity == null || !activeEntity.isValid()
                            || activeEntity.getWorld() != landing.getWorld()) {
                        finish(entity);
                        return;
                    }
                    double progress = Math.min(1.0D, (double) ++elapsedTicks / LEAP_TICKS);
                    Location position = start.clone().add(
                            (landing.getX() - start.getX()) * progress,
                            (landing.getY() - start.getY()) * progress
                                    + 1.5D * Math.sin(Math.PI * progress),
                            (landing.getZ() - start.getZ()) * progress
                    );
                    activeEntity.teleport(position);
                    active.currentLocation(position);
                    if (progress >= 1.0D) {
                        try {
                            impact(caster, landing, radius, damageRatio);
                        } finally {
                            finish(activeEntity);
                        }
                    }
                }

                private void finish(@NotNull Entity activeEntity) {
                    if (activeEntity.isValid()) {
                        activeEntity.setGravity(originalGravity);
                    }
                    caster.scriptedAction(false);
                    cancel();
                }
            }.runTaskTimer(mobService.plugin(), 0L, 1L);
        } catch (RuntimeException exception) {
            entity.setGravity(originalGravity);
            caster.scriptedAction(false);
            throw exception;
        }
    }

    private void impact(@NotNull MobInstance caster, @NotNull Location landing, double radius, double damageRatio) {
        World world = landing.getWorld();
        particleDisplayService.spawnForNearbyViewers(
                landing, ring(landing, radius), SharedParticleDefinitions.MOB_GRANBAL_BLOOM);
        world.playSound(landing, Sound.ENTITY_SHEEP_SHEAR, 1.0F, 0.9F);
        for (Entity entity : world.getNearbyEntities(landing, radius, 2.0D, radius)) {
            var victim = damageService.resolveEntity(entity);
            if (!damageService.isMobCombatTarget(entity)
                    || horizontalDistanceSquared(entity.getLocation(), landing) > radius * radius) {
                continue;
            }
            damageService.attack(
                    AstEntity.mob(caster), victim, AttackType.MELEE,
                    List.of(new DamageComponent(DamageElement.NONE, damageRatio)), DamageSource.SKILL);
        }
        temporarySkillEffectService.applyDefenseAndEvasionMultipliers(
                caster.instanceId(), EXPOSED_EFFECT_ID, EXPOSED_TICKS,
                EXPOSED_DEFENSE_MULTIPLIER, EXPOSED_EVASION_MULTIPLIER);
    }

    private @NotNull List<Location> ring(@NotNull Location center, double radius) {
        List<Location> points = new ArrayList<>(RING_POINTS);
        for (int index = 0; index < RING_POINTS; index++) {
            double angle = 2.0D * Math.PI * index / RING_POINTS;
            points.add(center.clone().add(Math.cos(angle) * radius, 0.15D, Math.sin(angle) * radius));
        }
        return points;
    }

    private Location landingAt(@NotNull Location target) {
        World world = target.getWorld();
        int x = target.getBlockX();
        int z = target.getBlockZ();
        for (int y = target.getBlockY(); y >= Math.max(world.getMinHeight(), target.getBlockY() - 4); y--) {
            Block ground = world.getBlockAt(x, y, z);
            if (!ground.isPassable()
                    && world.getBlockAt(x, y + 1, z).isPassable()
                    && world.getBlockAt(x, y + 2, z).isPassable()) {
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
}
