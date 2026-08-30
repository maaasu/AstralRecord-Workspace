package io.github.maaasu.astralRecord.feature.mob.skill.middleearth;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;

/**
 * {@code mob_iluvatar_flame_charge}: 残HP帯に応じて停止と炎突進を繰り返します。
 *
 * <p>任意パラメーターは {@code speed}（既定1.45）、{@code damageRatio}（既定0.85）、
 * {@code holdTicks}（既定16）です。回数は万物のエルと同じ残HP帯の式で決定します。</p>
 */
public final class IluvatarFlameChargeMobSkillExecutor implements MobSkillExecutor {

    public static final String SKILL_ID = "mob_iluvatar_flame_charge";
    private static final Set<String> PARAMETER_KEYS = Set.of("speed", "damageRatio", "holdTicks");
    private final MobService mobService;
    private final DamageService damageService;

    /** 実体移動と炎突進ダメージの依存先を指定して構築します。 */
    public IluvatarFlameChargeMobSkillExecutor(
            @NotNull MobService mobService,
            @NotNull DamageService damageService
    ) {
        this.mobService = mobService;
        this.damageService = damageService;
    }

    @Override public @NotNull String id() { return SKILL_ID; }
    @Override public @NotNull String displayName() { return "炎界突貫"; }
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(14.0D, 145L, 0L); }

    @Override
    public void validate(@NotNull MobSkillBinding binding) {
        Map<String, Double> params = binding.params();
        if (!PARAMETER_KEYS.containsAll(params.keySet())) {
            throw new IllegalArgumentException("Unsupported parameter for " + SKILL_ID);
        }
        bounded(params.getOrDefault("speed", 1.45D), "speed", 0.05D, 3.0D);
        bounded(params.getOrDefault("damageRatio", 0.85D), "damageRatio", 0.01D, 2.0D);
        bounded(params.getOrDefault("holdTicks", 16.0D), "holdTicks", 1.0D, 100.0D);
    }

    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        Entity entity = mobService.entityController().getEntity(context.mob());
        if (entity == null || entity.getWorld() != context.target().getWorld()) {
            return false;
        }
        Map<String, Double> params = context.binding().params();
        context.mob().scriptedAction(true);
        startNextCharge(
                context.mob(), context, AllThingsElChargeMobSkillExecutor.resolveChargeCount(context.mob()),
                params.getOrDefault("speed", 1.45D), params.getOrDefault("damageRatio", 0.85D),
                Math.round(params.getOrDefault("holdTicks", 16.0D))
        );
        return true;
    }

    private void startNextCharge(
            @NotNull MobInstance caster,
            @NotNull MobSkillContext context,
            int remainingCharges,
            double speed,
            double damageRatio,
            long holdTicks
    ) {
        Entity entity = mobService.entityController().getEntity(caster);
        if (entity == null || entity.isDead() || remainingCharges <= 0 || !context.target().isOnline()
                || context.target().isDead() || entity.getWorld() != context.target().getWorld()) {
            caster.scriptedAction(false);
            return;
        }
        Location targetSnapshot = context.target().getLocation();
        entity.setVelocity(entity.getVelocity().zero());
        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_BLAZE_AMBIENT, 0.8F, 0.7F);
        new BukkitRunnable() {
            private long elapsedTicks;

            @Override
            public void run() {
                Entity activeEntity = mobService.entityController().getEntity(caster);
                if (activeEntity == null || activeEntity.isDead()
                        || !MiddleEarthRushMotion.isTargetAvailable(activeEntity, context.target(), targetSnapshot)) {
                    cancel();
                    caster.scriptedAction(false);
                    return;
                }
                activeEntity.setVelocity(activeEntity.getVelocity().zero());
                if (++elapsedTicks < holdTicks) {
                    return;
                }
                cancel();
                activeEntity.getWorld().playSound(activeEntity.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.95F, 0.8F);
                MiddleEarthRushMotion.start(
                        mobService, damageService, caster, activeEntity, targetSnapshot, context.target(), speed,
                        damageRatio, DamageElement.FIRE,
                        () -> startNextCharge(caster, context, remainingCharges - 1, speed, damageRatio, holdTicks)
                );
            }
        }.runTaskTimer(mobService.plugin(), 0L, 1L);
    }

    private void bounded(double value, @NotNull String key, double minimum, double maximum) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
    }
}
