package io.github.maaasu.astralRecord.feature.mob.skill.skeletonarcher;

import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
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
 * {@code mob_skeleton_bow_shot}: スケルトン・アーチャーの回避可能な弓矢スキル。
 *
 * <p>必須パラメーターはありません。任意パラメーターは {@code damageRatio}（攻撃力倍率、既定0.85）、
 * {@code projectileSpeed}（tick あたりの飛距離、既定1.25）、{@code projectileHitRadius}
 * （hitbox の追加半径、既定0.20）です。未定義・未知のパラメーターは受け付けません。</p>
 */
public final class SkeletonArcherBowShotMobSkillExecutor implements MobSkillExecutor {

    public static final String SKILL_ID = "mob_skeleton_bow_shot";
    private static final Set<String> PARAMETER_KEYS = Set.of("damageRatio", "projectileSpeed", "projectileHitRadius");
    private final DamageService damageService;
    private final MobProjectileService projectileService;

    /** ダメージ適用と見える飛び道具の実行先を指定して構築します。 */
    public SkeletonArcherBowShotMobSkillExecutor(
            @NotNull DamageService damageService,
            @NotNull MobProjectileService projectileService
    ) {
        this.damageService = damageService;
        this.projectileService = projectileService;
    }

    @Override
    public @NotNull String id() {
        return SKILL_ID;
    }

    @Override
    public @NotNull String displayName() {
        return "狙いを定める";
    }

    @Override
    public @NotNull MobSkillTiming defaultTiming() {
        return new MobSkillTiming(16.0D, 36L, 12L);
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
        positive(params, "damageRatio", 0.85D);
        positive(params, "projectileSpeed", 1.25D);
        nonNegative(params, "projectileHitRadius", 0.20D);
    }

    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        Map<String, Double> params = context.binding().params();
        projectileService.launchArrow(
                context.mob(),
                context.origin(),
                context.direction(),
                params.getOrDefault("projectileSpeed", 1.25D),
                params.getOrDefault("projectileHitRadius", 0.20D),
                params.getOrDefault("damageRatio", 0.85D),
                damageService
        );
        context.origin().getWorld().playSound(context.origin(), Sound.ENTITY_ARROW_SHOOT, 0.9F, 0.95F);
        return true;
    }

    private void positive(@NotNull Map<String, Double> params, @NotNull String key, double fallback) {
        if (!Double.isFinite(params.getOrDefault(key, fallback)) || params.getOrDefault(key, fallback) <= 0.0D) {
            throw new IllegalArgumentException(key + " must be positive");
        }
    }

    private void nonNegative(@NotNull Map<String, Double> params, @NotNull String key, double fallback) {
        if (!Double.isFinite(params.getOrDefault(key, fallback)) || params.getOrDefault(key, fallback) < 0.0D) {
            throw new IllegalArgumentException(key + " must be zero or greater");
        }
    }
}
