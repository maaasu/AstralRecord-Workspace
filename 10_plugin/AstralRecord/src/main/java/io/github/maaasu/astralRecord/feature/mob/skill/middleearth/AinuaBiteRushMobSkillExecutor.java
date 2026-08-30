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
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code mob_ainua_bite_rush}: 対象を5回噛みつき、各攻撃後に短く前進します。
 *
 * <p>任意パラメーターは {@code damageRatio}（既定0.28）、{@code biteRange}（既定2.2）、
 * {@code stepDistance}（既定0.9）、{@code biteIntervalTicks}（既定6）です。噛みつき回数は
 * 戦闘契約として5回に固定し、マスターから変更できません。</p>
 */
public final class AinuaBiteRushMobSkillExecutor implements MobSkillExecutor {

    public static final String SKILL_ID = "mob_ainua_bite_rush";
    public static final int BITE_COUNT = 5;
    private static final Set<String> PARAMETER_KEYS = Set.of(
            "damageRatio", "biteRange", "stepDistance", "biteIntervalTicks"
    );

    private final MobService mobService;
    private final DamageService damageService;

    /** Mob実体の制御先とダメージ適用先を指定して構築します。 */
    public AinuaBiteRushMobSkillExecutor(
            @NotNull MobService mobService,
            @NotNull DamageService damageService
    ) {
        this.mobService = mobService;
        this.damageService = damageService;
    }

    @Override public @NotNull String id() { return SKILL_ID; }
    @Override public @NotNull String displayName() { return "五連咬進"; }
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(4.0D, 100L, 10L); }

    @Override
    public void validate(@NotNull MobSkillBinding binding) {
        Map<String, Double> params = binding.params();
        if (!PARAMETER_KEYS.containsAll(params.keySet())) {
            throw new IllegalArgumentException("Unsupported parameter for " + SKILL_ID);
        }
        bounded(params.getOrDefault("damageRatio", 0.28D), "damageRatio", 0.01D, 2.0D);
        bounded(params.getOrDefault("biteRange", 2.2D), "biteRange", 0.5D, 4.0D);
        bounded(params.getOrDefault("stepDistance", 0.9D), "stepDistance", 0.1D, 2.0D);
        bounded(params.getOrDefault("biteIntervalTicks", 6.0D), "biteIntervalTicks", 1.0D, 40.0D);
    }

    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        Entity entity = mobService.entityController().getEntity(context.mob());
        if (entity == null || entity.getWorld() != context.target().getWorld()) {
            return false;
        }

        Map<String, Double> params = context.binding().params();
        double damageRatio = params.getOrDefault("damageRatio", 0.28D);
        double biteRange = params.getOrDefault("biteRange", 2.2D);
        double stepDistance = params.getOrDefault("stepDistance", 0.9D);
        long biteIntervalTicks = Math.round(params.getOrDefault("biteIntervalTicks", 6.0D));
        context.mob().scriptedAction(true);
        startSequence(context, damageRatio, biteRange, stepDistance, biteIntervalTicks);
        return true;
    }

    private void startSequence(
            @NotNull MobSkillContext context,
            double damageRatio,
            double biteRange,
            double stepDistance,
            long biteIntervalTicks
    ) {
        new BukkitRunnable() {
            private int bites;

            @Override
            public void run() {
                MobInstance active = mobService.getInstance(context.mob().instanceId());
                Entity entity = active == context.mob() ? mobService.entityController().getEntity(active) : null;
                if (entity == null || entity.isDead() || !context.target().isOnline() || context.target().isDead()
                        || entity.getWorld() != context.target().getWorld()) {
                    complete();
                    return;
                }

                Location origin = entity.getLocation();
                Location target = context.target().getLocation();
                Vector offset = target.toVector().subtract(origin.toVector());
                double horizontalDistance = Math.hypot(offset.getX(), offset.getZ());
                if (horizontalDistance <= biteRange) {
                    damageService.attack(
                            AstEntity.mob(active), damageService.resolveEntity(context.target()), AttackType.MELEE,
                            List.of(new DamageComponent(DamageElement.NONE, damageRatio)), DamageSource.SKILL
                    );
                    entity.getWorld().playSound(origin, Sound.ENTITY_FOX_BITE, 0.85F, 0.8F);
                }
                stepToward(active, entity, offset, horizontalDistance, stepDistance);
                bites++;
                if (bites >= BITE_COUNT) {
                    complete();
                }
            }

            private void complete() {
                cancel();
                context.mob().scriptedAction(false);
            }
        }.runTaskTimer(mobService.plugin(), 0L, Math.max(1L, biteIntervalTicks));
    }

    private void stepToward(
            @NotNull MobInstance mob,
            @NotNull Entity entity,
            @NotNull Vector offset,
            double horizontalDistance,
            double stepDistance
    ) {
        if (horizontalDistance <= 0.01D) {
            return;
        }
        Vector direction = new Vector(offset.getX(), 0.0D, offset.getZ()).normalize();
        double distance = Math.min(stepDistance, Math.max(0.0D, horizontalDistance - 0.5D));
        if (distance <= 0.01D || entity.getWorld().rayTraceBlocks(entity.getLocation(), direction, distance) != null) {
            return;
        }
        Location next = entity.getLocation().add(direction.multiply(distance));
        entity.teleport(next);
        mob.currentLocation(next);
    }

    private void bounded(double value, @NotNull String key, double minimum, double maximum) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
    }
}
