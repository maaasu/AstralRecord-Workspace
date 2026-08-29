package io.github.maaasu.astralRecord.feature.mob.skill.forestspider;

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
 * {@code mob_forest_spider_web_shot}: フォレストスパイダーが近距離へクモ糸を飛ばす遠隔スキル。
 *
 * <p>任意パラメーターは {@code damageRatio}（攻撃力倍率、既定0.75）、
 * {@code projectileSpeed}（tick あたりの飛距離、既定0.90）、{@code projectileHitRadius}
 * （hitbox の追加半径、既定0.25）、{@code weaknessChance}（衰弱の基礎付与確率、既定25%）、
 * {@code weaknessDurationTicks}（衰弱時間、既定100 tick）です。未定義・未知のパラメーターは受け付けません。</p>
 */
public final class ForestSpiderWebShotMobSkillExecutor implements MobSkillExecutor {

    public static final String SKILL_ID = "mob_forest_spider_web_shot";
    private static final Set<String> PARAMETER_KEYS = Set.of(
            "damageRatio", "projectileSpeed", "projectileHitRadius", "weaknessChance", "weaknessDurationTicks"
    );
    private static final double DEFAULT_DAMAGE_RATIO = 0.75D;
    private static final double DEFAULT_PROJECTILE_SPEED = 0.90D;
    private static final double DEFAULT_PROJECTILE_HIT_RADIUS = 0.25D;
    private static final double DEFAULT_WEAKNESS_CHANCE = 25.0D;
    private static final double DEFAULT_WEAKNESS_DURATION_TICKS = 100.0D;

    private final DamageService damageService;
    private final ConditionService conditionService;
    private final MobProjectileService projectileService;

    /** ダメージ・状態異常・見える飛び道具の依存先を指定して構築します。 */
    public ForestSpiderWebShotMobSkillExecutor(
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
        return "クモ糸射出";
    }

    @Override
    public @NotNull MobSkillTiming defaultTiming() {
        return new MobSkillTiming(9.0D, 40L, 10L);
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
        bounded(params.getOrDefault("damageRatio", DEFAULT_DAMAGE_RATIO), "damageRatio", 0.01D, Double.MAX_VALUE);
        bounded(params.getOrDefault("projectileSpeed", DEFAULT_PROJECTILE_SPEED), "projectileSpeed", 0.05D, Double.MAX_VALUE);
        bounded(params.getOrDefault("projectileHitRadius", DEFAULT_PROJECTILE_HIT_RADIUS), "projectileHitRadius", 0.0D, 2.0D);
        bounded(params.getOrDefault("weaknessChance", DEFAULT_WEAKNESS_CHANCE), "weaknessChance", 0.0D, 100.0D);
        bounded(params.getOrDefault("weaknessDurationTicks", DEFAULT_WEAKNESS_DURATION_TICKS), "weaknessDurationTicks", 1.0D, 600.0D);
    }

    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        if (context.origin().getWorld() == null) {
            return false;
        }
        Map<String, Double> params = context.binding().params();
        projectileService.launchWeb(
                context.mob(),
                context.origin(),
                context.direction(),
                params.getOrDefault("projectileSpeed", DEFAULT_PROJECTILE_SPEED),
                params.getOrDefault("projectileHitRadius", DEFAULT_PROJECTILE_HIT_RADIUS),
                params.getOrDefault("damageRatio", DEFAULT_DAMAGE_RATIO),
                Math.round(params.getOrDefault("weaknessDurationTicks", DEFAULT_WEAKNESS_DURATION_TICKS)),
                params.getOrDefault("weaknessChance", DEFAULT_WEAKNESS_CHANCE),
                damageService,
                conditionService
        );
        context.origin().getWorld().playSound(context.origin(), Sound.ENTITY_SPIDER_AMBIENT, 0.9F, 1.1F);
        return true;
    }

    private void bounded(double value, @NotNull String key, double minimum, double maximum) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
    }
}
