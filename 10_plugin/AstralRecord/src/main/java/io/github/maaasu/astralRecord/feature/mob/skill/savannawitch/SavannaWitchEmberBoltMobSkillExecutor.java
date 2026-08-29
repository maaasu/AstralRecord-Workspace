package io.github.maaasu.astralRecord.feature.mob.skill.savannawitch;

import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.feature.mob.service.MobProjectileService;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;

/**
 * {@code mob_savanna_witch_ember_bolt}: 緩く追尾する炎弾で、凍結と燃焼を個別確率で付与します。
 *
 * <p>任意パラメーターは {@code damageRatio}、{@code projectileSpeed}、{@code projectileHitRadius}、
 * {@code homingStrength}、{@code frozenDurationTicks}、{@code frozenChance}、
 * {@code burningDurationTicks}、{@code burningChance} です。確率は 0〜100 の百分率で指定します。</p>
 */
public final class SavannaWitchEmberBoltMobSkillExecutor implements MobSkillExecutor {

    public static final String SKILL_ID = "mob_savanna_witch_ember_bolt";
    private static final Set<String> PARAMETER_KEYS = Set.of(
            "damageRatio", "projectileSpeed", "projectileHitRadius", "homingStrength",
            "frozenDurationTicks", "frozenChance", "burningDurationTicks", "burningChance"
    );

    private final DamageService damageService;
    private final ConditionService conditionService;
    private final MobProjectileService projectileService;

    /** ダメージ、状態異常、追尾弾の実行先を指定して構築します。 */
    public SavannaWitchEmberBoltMobSkillExecutor(
            @NotNull DamageService damageService,
            @NotNull ConditionService conditionService,
            @NotNull MobProjectileService projectileService
    ) {
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.projectileService = projectileService;
    }

    @Override
    public @NotNull String id() {
        return SKILL_ID;
    }

    @Override
    public @NotNull String displayName() {
        return "灼氷弾";
    }

    @Override
    public @NotNull MobSkillTiming defaultTiming() {
        return new MobSkillTiming(16.0D, 48L, 12L);
    }

    @Override
    public boolean allowsVerticalTargeting() {
        return true;
    }

    @Override
    public void validate(@NotNull MobSkillBinding binding) {
        Map<String, Double> params = binding.params();
        if (!PARAMETER_KEYS.containsAll(params.keySet())) {
            throw new IllegalArgumentException("Unsupported parameter for " + SKILL_ID);
        }
        positive(params, "damageRatio", 0.75D);
        positive(params, "projectileSpeed", 0.85D);
        nonNegative(params, "projectileHitRadius", 0.25D);
        bounded(params, "homingStrength", 0.12D, 0.0D, 1.0D);
        positive(params, "frozenDurationTicks", 20.0D);
        percentage(params, "frozenChance", 18.0D);
        positive(params, "burningDurationTicks", 60.0D);
        percentage(params, "burningChance", 24.0D);
    }

    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        Map<String, Double> params = context.binding().params();
        projectileService.launchHomingElementalBolt(
                context.mob(), context.origin(), context.direction(), context.target().getUniqueId(),
                params.getOrDefault("projectileSpeed", 0.85D),
                params.getOrDefault("projectileHitRadius", 0.25D),
                params.getOrDefault("homingStrength", 0.12D),
                params.getOrDefault("damageRatio", 0.75D),
                Math.round(params.getOrDefault("frozenDurationTicks", 20.0D)),
                params.getOrDefault("frozenChance", 18.0D),
                Math.round(params.getOrDefault("burningDurationTicks", 60.0D)),
                params.getOrDefault("burningChance", 24.0D),
                damageService, conditionService
        );
        context.origin().getWorld().playSound(context.origin(), Sound.ENTITY_WITCH_THROW, 0.9F, 0.85F);
        return true;
    }

    private void positive(@NotNull Map<String, Double> params, @NotNull String key, double fallback) {
        bounded(params, key, fallback, Double.MIN_VALUE, Double.MAX_VALUE);
    }

    private void nonNegative(@NotNull Map<String, Double> params, @NotNull String key, double fallback) {
        bounded(params, key, fallback, 0.0D, Double.MAX_VALUE);
    }

    private void percentage(@NotNull Map<String, Double> params, @NotNull String key, double fallback) {
        bounded(params, key, fallback, 0.0D, 100.0D);
    }

    private void bounded(@NotNull Map<String, Double> params, @NotNull String key, double fallback, double minimum, double maximum) {
        double value = params.getOrDefault(key, fallback);
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
    }
}
