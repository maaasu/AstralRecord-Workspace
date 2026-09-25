package io.github.maaasu.astralRecord.feature.mob.skill.vinespider;

import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.feature.mob.service.MobProjectileService;
import org.bukkit.Sound;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;

/**
 * {@code mob_vine_spider_fan_web}: ツタクモが回避可能な三方向の糸を扇状に放ちます。
 *
 * <p>任意パラメーターは {@code damageRatio}（糸1本ごとの攻撃力倍率、既定0.22）、
 * {@code spreadDegrees}（中央から左右へ開く角度、既定18度）、
 * {@code weaknessChance}（命中時の衰弱基礎付与確率、既定18%）です。
 * 各糸は既存の見える飛び道具として壁とプレイヤーに個別に衝突します。</p>
 */
public final class VineSpiderFanWebMobSkillExecutor implements MobSkillExecutor {

    public static final String SKILL_ID = "mob_vine_spider_fan_web";
    private static final Set<String> PARAMETER_KEYS = Set.of("damageRatio", "spreadDegrees", "weaknessChance");
    private static final double DEFAULT_DAMAGE_RATIO = 0.22D;
    private static final double DEFAULT_SPREAD_DEGREES = 18.0D;
    private static final double DEFAULT_WEAKNESS_CHANCE = 18.0D;
    private static final double PROJECTILE_SPEED = 0.82D;
    private static final double PROJECTILE_HIT_RADIUS = 0.22D;
    private static final long WEAKNESS_DURATION_TICKS = 80L;

    private final DamageService damageService;
    private final ConditionService conditionService;
    private final MobProjectileService projectileService;

    /**
     * ダメージ、状態異常、見える飛び道具の依存先を指定して構築します。
     *
     * @param damageService 命中時のダメージ適用先
     * @param conditionService 命中時の状態異常適用先
     * @param projectileService 糸の移動・壁・hitbox判定先
     */
    public VineSpiderFanWebMobSkillExecutor(
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
        return "ツタ糸の三連射";
    }

    @Override
    public @NotNull MobSkillTiming defaultTiming() {
        return new MobSkillTiming(10.0D, 70L, 16L);
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
        bounded(params.getOrDefault("damageRatio", DEFAULT_DAMAGE_RATIO), "damageRatio", 0.01D, 1.0D);
        bounded(params.getOrDefault("spreadDegrees", DEFAULT_SPREAD_DEGREES), "spreadDegrees", 1.0D, 45.0D);
        bounded(params.getOrDefault("weaknessChance", DEFAULT_WEAKNESS_CHANCE), "weaknessChance", 0.0D, 100.0D);
    }

    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        if (context.origin().getWorld() == null || context.direction().lengthSquared() <= 1.0E-6D) {
            return false;
        }
        Map<String, Double> params = context.binding().params();
        double damageRatio = params.getOrDefault("damageRatio", DEFAULT_DAMAGE_RATIO);
        double spreadDegrees = params.getOrDefault("spreadDegrees", DEFAULT_SPREAD_DEGREES);
        double weaknessChance = params.getOrDefault("weaknessChance", DEFAULT_WEAKNESS_CHANCE);
        for (double angle : new double[]{-spreadDegrees, 0.0D, spreadDegrees}) {
            projectileService.launchWeb(
                    context.mob(),
                    context.origin(),
                    rotateAroundY(context.direction(), Math.toRadians(angle)),
                    PROJECTILE_SPEED,
                    PROJECTILE_HIT_RADIUS,
                    damageRatio,
                    WEAKNESS_DURATION_TICKS,
                    weaknessChance,
                    damageService,
                    conditionService
            );
        }
        context.origin().getWorld().playSound(context.origin(), Sound.ENTITY_SPIDER_AMBIENT, 0.9F, 0.72F);
        return true;
    }

    private @NotNull Vector rotateAroundY(@NotNull Vector direction, double radians) {
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        return new Vector(
                direction.getX() * cosine - direction.getZ() * sine,
                direction.getY(),
                direction.getX() * sine + direction.getZ() * cosine
        ).normalize();
    }

    private void bounded(double value, @NotNull String key, double minimum, double maximum) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
    }
}
