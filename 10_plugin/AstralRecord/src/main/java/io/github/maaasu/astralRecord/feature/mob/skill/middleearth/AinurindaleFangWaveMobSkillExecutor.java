package io.github.maaasu.astralRecord.feature.mob.skill.middleearth;

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
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code mob_ainurindale_fang_wave}: 前方へ三方向の地走り魔法を3波発生させます。
 *
 * <p>任意パラメーターは {@code damageRatio}（既定0.45）、{@code hitRadius}（既定1.25）、
 * {@code laneSpacing}（既定1.8）、{@code waveIntervalTicks}（既定8）です。波数は3に固定し、
 * 各波では同じプレイヤーへ一度だけダメージを与えます。</p>
 */
public final class AinurindaleFangWaveMobSkillExecutor implements MobSkillExecutor {

    public static final String SKILL_ID = "mob_ainurindale_fang_wave";
    public static final int WAVE_COUNT = 3;
    private static final Set<String> PARAMETER_KEYS = Set.of(
            "damageRatio", "hitRadius", "laneSpacing", "waveIntervalTicks"
    );

    private final MobService mobService;
    private final DamageService damageService;
    private final ParticleDisplayService particleDisplayService;

    /** Mob実体、ダメージ、演出の依存先を指定して構築します。 */
    public AinurindaleFangWaveMobSkillExecutor(
            @NotNull MobService mobService,
            @NotNull DamageService damageService,
            @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.mobService = mobService;
        this.damageService = damageService;
        this.particleDisplayService = particleDisplayService;
    }

    @Override public @NotNull String id() { return SKILL_ID; }
    @Override public @NotNull String displayName() { return "三界牙陣"; }
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(14.0D, 100L, 20L); }

    @Override
    public void validate(@NotNull MobSkillBinding binding) {
        Map<String, Double> params = binding.params();
        if (!PARAMETER_KEYS.containsAll(params.keySet())) {
            throw new IllegalArgumentException("Unsupported parameter for " + SKILL_ID);
        }
        bounded(params.getOrDefault("damageRatio", 0.45D), "damageRatio", 0.01D, 2.0D);
        bounded(params.getOrDefault("hitRadius", 1.25D), "hitRadius", 0.25D, 3.0D);
        bounded(params.getOrDefault("laneSpacing", 1.8D), "laneSpacing", 0.5D, 4.0D);
        bounded(params.getOrDefault("waveIntervalTicks", 8.0D), "waveIntervalTicks", 1.0D, 40.0D);
    }

    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        Entity entity = mobService.entityController().getEntity(context.mob());
        if (entity == null || entity.getWorld() != context.target().getWorld()) {
            return false;
        }
        Location groundOrigin = entity.getLocation();
        Vector direction = horizontalDirection(groundOrigin, context.target().getLocation(), entity.getFacing().getDirection());
        Map<String, Double> params = context.binding().params();
        context.mob().scriptedAction(true);
        startWaves(
                context.mob(), groundOrigin, direction,
                params.getOrDefault("damageRatio", 0.45D),
                params.getOrDefault("hitRadius", 1.25D),
                params.getOrDefault("laneSpacing", 1.8D),
                Math.round(params.getOrDefault("waveIntervalTicks", 8.0D))
        );
        return true;
    }

    private void startWaves(
            @NotNull MobInstance caster,
            @NotNull Location origin,
            @NotNull Vector direction,
            double damageRatio,
            double hitRadius,
            double laneSpacing,
            long waveIntervalTicks
    ) {
        new BukkitRunnable() {
            private int waveIndex;

            @Override
            public void run() {
                MobInstance active = mobService.getInstance(caster.instanceId());
                Entity entity = active == caster ? mobService.entityController().getEntity(active) : null;
                if (entity == null || entity.isDead() || entity.getWorld() != origin.getWorld()) {
                    complete();
                    return;
                }
                List<Location> centers = waveCenters(origin, direction, waveIndex, laneSpacing);
                renderWave(centers, hitRadius);
                damageWave(active, centers, hitRadius, damageRatio);
                origin.getWorld().playSound(centers.get(1), Sound.ENTITY_EVOKER_CAST_SPELL, 0.9F, 0.75F + 0.1F * waveIndex);
                waveIndex++;
                if (waveIndex >= WAVE_COUNT) {
                    complete();
                }
            }

            private void complete() {
                cancel();
                caster.scriptedAction(false);
            }
        }.runTaskTimer(mobService.plugin(), 0L, Math.max(1L, waveIntervalTicks));
    }

    /**
     * 波番号に応じた前方距離へ、左・中央・右の3地点を返します。
     *
     * @param origin 発動起点
     * @param direction 水平方向
     * @param waveIndex 0始まりの波番号
     * @param laneSpacing 左右の間隔
     * @return 左・中央・右の順の攻撃中心
     */
    public static @NotNull List<Location> waveCenters(
            @NotNull Location origin,
            @NotNull Vector direction,
            int waveIndex,
            double laneSpacing
    ) {
        Vector forward = new Vector(direction.getX(), 0.0D, direction.getZ());
        if (forward.lengthSquared() <= 1.0E-6D) {
            forward = new Vector(0.0D, 0.0D, 1.0D);
        }
        forward.normalize();
        Vector side = new Vector(-forward.getZ(), 0.0D, forward.getX());
        Location center = origin.clone().add(forward.multiply(3.0D + Math.max(0, waveIndex) * 2.5D));
        return List.of(
                center.clone().add(side.clone().multiply(laneSpacing)),
                center,
                center.clone().subtract(side.clone().multiply(laneSpacing))
        );
    }

    private void renderWave(@NotNull List<Location> centers, double radius) {
        List<Location> points = new ArrayList<>(centers.size() * 16);
        for (Location center : centers) {
            for (int index = 0; index < 16; index++) {
                double angle = Math.PI * 2.0D * index / 16.0D;
                points.add(center.clone().add(Math.cos(angle) * radius, 0.1D, Math.sin(angle) * radius));
            }
        }
        particleDisplayService.spawnForNearbyViewers(
                centers.get(1), points, SharedParticleDefinitions.SKILL_MAGE_ARCANE_DUST
        );
    }

    private void damageWave(
            @NotNull MobInstance caster,
            @NotNull List<Location> centers,
            double hitRadius,
            double damageRatio
    ) {
        Set<UUID> damaged = new HashSet<>();
        for (Player player : centers.get(1).getWorld().getPlayers()) {
            if (!player.isOnline() || player.isDead() || !isGameplayTargetPlayer(player)
                    || !damaged.add(player.getUniqueId())) {
                continue;
            }
            boolean hit = centers.stream().anyMatch(center -> isWithinHitRadius(player.getLocation(), center, hitRadius));
            if (!hit) {
                continue;
            }
            damageService.attack(
                    AstEntity.mob(caster), damageService.resolveEntity(player), AttackType.MAGIC,
                    List.of(new DamageComponent(DamageElement.NONE, damageRatio)), DamageSource.SKILL
            );
        }
    }

    private boolean isGameplayTargetPlayer(@NotNull Player player) {
        return player.getUniqueId() != null && AccountModeGuard.isGameplayPlayer(player);
    }

    private @NotNull Vector horizontalDirection(
            @NotNull Location origin,
            @NotNull Location target,
            @NotNull Vector fallback
    ) {
        Vector direction = target.toVector().subtract(origin.toVector());
        direction.setY(0.0D);
        if (direction.lengthSquared() <= 1.0E-6D) {
            direction = fallback.clone().setY(0.0D);
        }
        return direction.lengthSquared() <= 1.0E-6D ? new Vector(0.0D, 0.0D, 1.0D) : direction.normalize();
    }

    static boolean isWithinHitRadius(
            @NotNull Location playerLocation,
            @NotNull Location center,
            double hitRadius
    ) {
        return playerLocation.getWorld() == center.getWorld()
                && playerLocation.distanceSquared(center) <= hitRadius * hitRadius;
    }

    private void bounded(double value, @NotNull String key, double minimum, double maximum) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
    }
}
