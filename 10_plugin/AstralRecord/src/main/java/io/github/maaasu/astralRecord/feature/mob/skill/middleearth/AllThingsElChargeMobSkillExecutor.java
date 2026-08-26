package io.github.maaasu.astralRecord.feature.mob.skill.middleearth;

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

/** {@code mob_all_things_el_charge}: HP帯に応じて、停止して狙った位置へ連続突進します。 */
public final class AllThingsElChargeMobSkillExecutor implements MobSkillExecutor {

    public static final String SKILL_ID = "mob_all_things_el_charge";
    private static final Set<String> PARAMETER_KEYS = Set.of("speed", "damageRatio", "holdTicks");
    private final MobService mobService;
    private final DamageService damageService;

    /** 実体移動と突進ダメージの依存先を指定して構築します。 */
    public AllThingsElChargeMobSkillExecutor(@NotNull MobService mobService, @NotNull DamageService damageService) {
        this.mobService = mobService;
        this.damageService = damageService;
    }

    @Override public @NotNull String id() { return SKILL_ID; }
    @Override public @NotNull String displayName() { return "万象突貫"; }
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(14.0D, 160L, 0L); }

    @Override
    public void validate(@NotNull MobSkillBinding binding) {
        if (!PARAMETER_KEYS.containsAll(binding.params().keySet())) {
            throw new IllegalArgumentException("Unsupported parameter for " + SKILL_ID);
        }
        positive(binding.params().getOrDefault("speed", 1.40D), "speed");
        positive(binding.params().getOrDefault("damageRatio", 1.00D), "damageRatio");
        bounded(binding.params().getOrDefault("holdTicks", 20.0D), "holdTicks", 1.0D, 100.0D);
    }

    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        Entity entity = mobService.entityController().getEntity(context.mob());
        if (entity == null || entity.getWorld() != context.target().getWorld()) {
            return false;
        }
        Map<String, Double> params = context.binding().params();
        context.mob().scriptedAction(true);
        startNextCharge(context.mob(), context, resolveChargeCount(context.mob()),
                params.getOrDefault("speed", 1.40D), params.getOrDefault("damageRatio", 1.00D),
                Math.round(params.getOrDefault("holdTicks", 20.0D)));
        return true;
    }

    /** HP割合の一の位を切り捨て、{@code 10 - floor(HP% / 10)} 回へ変換します。 */
    public static int resolveChargeCount(@NotNull MobInstance mob) {
        return resolveChargeCount(mob.currentHealth(), mob.maxHealth());
    }

    /** HP割合の一の位を切り捨て、突進回数へ変換します。 */
    public static int resolveChargeCount(double currentHealth, double maxHealth) {
        double percent = 100.0D * currentHealth / Math.max(1.0D, maxHealth);
        return Math.clamp(10 - (int) Math.floor(percent / 10.0D), 1, 10);
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
        if (entity == null || entity.isDead() || remainingCharges <= 0 || !context.target().isOnline() || context.target().isDead()) {
            caster.scriptedAction(false);
            return;
        }
        Location targetSnapshot = context.target().getLocation();
        entity.setVelocity(entity.getVelocity().zero());
        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_BREEZE_IDLE_GROUND, 0.8F, 0.7F);
        new BukkitRunnable() {
            private long elapsedTicks;

            @Override
            public void run() {
                Entity activeEntity = mobService.entityController().getEntity(caster);
                if (activeEntity == null || activeEntity.isDead()) {
                    cancel();
                    caster.scriptedAction(false);
                    return;
                }
                activeEntity.setVelocity(activeEntity.getVelocity().zero());
                if (++elapsedTicks < holdTicks) {
                    return;
                }
                cancel();
                activeEntity.getWorld().playSound(activeEntity.getLocation(), Sound.ENTITY_BREEZE_SHOOT, 0.9F, 1.0F);
                MiddleEarthRushMotion.start(
                        mobService, damageService, caster, activeEntity, targetSnapshot, context.target(), speed, damageRatio,
                        () -> startNextCharge(caster, context, remainingCharges - 1, speed, damageRatio, holdTicks)
                );
            }
        }.runTaskTimer(mobService.plugin(), 0L, 1L);
    }

    private void positive(double value, @NotNull String key) { bounded(value, key, Double.MIN_VALUE, Double.MAX_VALUE); }
    private void bounded(double value, @NotNull String key, double minimum, double maximum) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
    }
}
