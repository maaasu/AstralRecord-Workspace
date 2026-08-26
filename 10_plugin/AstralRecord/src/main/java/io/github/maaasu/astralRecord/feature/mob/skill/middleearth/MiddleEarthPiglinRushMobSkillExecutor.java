package io.github.maaasu.astralRecord.feature.mob.skill.middleearth;

import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;

/** {@code mob_middle_earth_piglin_rush}: 詠唱時点のターゲット位置へ一直線に突進します。 */
public final class MiddleEarthPiglinRushMobSkillExecutor implements MobSkillExecutor {

    public static final String SKILL_ID = "mob_middle_earth_piglin_rush";
    private static final Set<String> PARAMETER_KEYS = Set.of("speed", "damageRatio");
    private final MobService mobService;
    private final DamageService damageService;

    /** 実体移動と突進ダメージの依存先を指定して構築します。 */
    public MiddleEarthPiglinRushMobSkillExecutor(@NotNull MobService mobService, @NotNull DamageService damageService) {
        this.mobService = mobService;
        this.damageService = damageService;
    }

    @Override public @NotNull String id() { return SKILL_ID; }
    @Override public @NotNull String displayName() { return "猪突猛進"; }
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(8.0D, 100L, 30L); }

    @Override
    public void validate(@NotNull MobSkillBinding binding) {
        if (!PARAMETER_KEYS.containsAll(binding.params().keySet())) {
            throw new IllegalArgumentException("Unsupported parameter for " + SKILL_ID);
        }
        positive(binding.params().getOrDefault("speed", 1.10D), "speed");
        positive(binding.params().getOrDefault("damageRatio", 0.90D), "damageRatio");
    }

    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        Entity entity = mobService.entityController().getEntity(context.mob());
        if (entity == null || entity.getWorld() != context.target().getWorld()) {
            return false;
        }
        context.mob().scriptedAction(true);
        entity.setVelocity(entity.getVelocity().zero());
        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_PIGLIN_ANGRY, 0.9F, 0.8F);
        var targetSnapshot = context.origin().clone().add(context.direction())
                .subtract(0.0D, context.target().getEyeHeight(), 0.0D);
        MiddleEarthRushMotion.start(
                mobService, damageService, context.mob(), entity,
                targetSnapshot, context.target(),
                context.binding().params().getOrDefault("speed", 1.10D),
                context.binding().params().getOrDefault("damageRatio", 0.90D),
                () -> context.mob().scriptedAction(false)
        );
        return true;
    }

    private void positive(double value, @NotNull String key) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalArgumentException(key + " must be positive");
        }
    }
}
