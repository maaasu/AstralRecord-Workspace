package io.github.maaasu.astralRecord.feature.mob.skill.eriva;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code mob_eriva_starhorn_grove_circle}: 対象の足元を固定して光の輪を予告し、20 tick後に一度だけ魔法を噴き上げます。
 *
 * <p>任意パラメーターは {@code radius}（既定2.5m、0.5～5m）と
 * {@code damageRatio}（既定0.8、0.01～2.0）です。地形は変更しません。</p>
 */
public final class ErivaStarhornGroveCircleMobSkillExecutor implements MobSkillExecutor {

    public static final String SKILL_ID = "mob_eriva_starhorn_grove_circle";
    private static final Set<String> PARAMETER_KEYS = Set.of("radius", "damageRatio");
    private static final double DEFAULT_RADIUS = 2.5D;
    private static final double DEFAULT_DAMAGE_RATIO = 0.8D;
    private static final int WARNING_POINTS = 24;
    private static final int WARNING_INTERVAL_TICKS = 5;
    private static final int IMPACT_DELAY_TICKS = 20;

    private final MobService mobService;
    private final DamageService damageService;
    private final ParticleDisplayService particleDisplayService;

    /**
     * Mobの存続確認、ダメージ適用、演出表示の依存先を指定して構築します。
     *
     * @param mobService 発動者の実体とPluginの取得先
     * @param damageService 範囲ダメージの適用先
     * @param particleDisplayService 予告と着弾演出の表示先
     */
    public ErivaStarhornGroveCircleMobSkillExecutor(
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
        return "星角の樹光陣";
    }

    @Override
    public @NotNull MobSkillTiming defaultTiming() {
        return new MobSkillTiming(9.0D, 100L, 20L);
    }

    @Override
    public void validate(@NotNull MobSkillBinding binding) {
        Map<String, Double> params = binding.params();
        if (!PARAMETER_KEYS.containsAll(params.keySet())) {
            throw new IllegalArgumentException("Unsupported parameter for " + SKILL_ID);
        }
        bounded(params.getOrDefault("radius", DEFAULT_RADIUS), "radius", 0.5D, 5.0D);
        bounded(params.getOrDefault("damageRatio", DEFAULT_DAMAGE_RATIO), "damageRatio", 0.01D, 2.0D);
    }

    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        Entity casterEntity = mobService.entityController().getEntity(context.mob());
        Location center = resolveGround(context.target().getLocation());
        if (casterEntity == null || casterEntity.isDead() || center == null
                || casterEntity.getWorld() != center.getWorld()) {
            return false;
        }
        double radius = context.binding().params().getOrDefault("radius", DEFAULT_RADIUS);
        double damageRatio = context.binding().params().getOrDefault("damageRatio", DEFAULT_DAMAGE_RATIO);
        renderWarning(center, radius);
        center.getWorld().playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9F, 0.9F);
        startWarning(context.mob(), center, radius, damageRatio);
        return true;
    }

    private void startWarning(@NotNull MobInstance caster, @NotNull Location center, double radius, double damageRatio) {
        new BukkitRunnable() {
            private int elapsedTicks;

            @Override
            public void run() {
                MobInstance active = mobService.getInstance(caster.instanceId());
                Entity entity = active == caster ? mobService.entityController().getEntity(active) : null;
                if (entity == null || entity.isDead() || entity.getWorld() != center.getWorld()) {
                    cancel();
                    return;
                }
                elapsedTicks += WARNING_INTERVAL_TICKS;
                if (elapsedTicks >= IMPACT_DELAY_TICKS) {
                    impact(active, center, radius, damageRatio);
                    cancel();
                    return;
                }
                renderWarning(center, radius);
            }
        }.runTaskTimer(mobService.plugin(), WARNING_INTERVAL_TICKS, WARNING_INTERVAL_TICKS);
    }

    private void impact(@NotNull MobInstance caster, @NotNull Location center, double radius, double damageRatio) {
        World world = center.getWorld();
        particleDisplayService.spawnForNearbyViewers(
                center, circlePoints(center, radius, 0.35D), SharedParticleDefinitions.MOB_ERIVA_STARHORN_BLOOM
        );
        world.playSound(center, Sound.BLOCK_AZALEA_LEAVES_BREAK, 1.0F, 0.75F);
        for (Entity entity : world.getNearbyEntities(center, radius, 2.0D, radius)) {
            if (!(entity instanceof Player player) || player.isDead()
                    || !AccountModeGuard.isGameplayPlayer(player)
                    || horizontalDistanceSquared(player.getLocation(), center) > radius * radius) {
                continue;
            }
            damageService.attack(
                    AstEntity.mob(caster), damageService.resolveEntity(player), AttackType.MAGIC,
                    List.of(new DamageComponent(DamageElement.NONE, damageRatio)), DamageSource.SKILL
            );
        }
    }

    private void renderWarning(@NotNull Location center, double radius) {
        particleDisplayService.spawnForNearbyViewers(
                center, circlePoints(center, radius, 0.08D), SharedParticleDefinitions.MOB_ERIVA_STARHORN_WARNING
        );
    }

    private @NotNull List<Location> circlePoints(@NotNull Location center, double radius, double height) {
        List<Location> points = new ArrayList<>(WARNING_POINTS);
        for (int index = 0; index < WARNING_POINTS; index++) {
            double angle = Math.PI * 2.0D * index / WARNING_POINTS;
            points.add(center.clone().add(Math.cos(angle) * radius, height, Math.sin(angle) * radius));
        }
        return points;
    }

    private Location resolveGround(@NotNull Location target) {
        World world = target.getWorld();
        if (world == null) {
            return null;
        }
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
