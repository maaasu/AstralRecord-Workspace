package io.github.maaasu.astralRecord.feature.skill.executor.active.mage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileTermination;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 氷球を飛ばし、最初の着弾地点へ小規模な氷属性範囲攻撃を行うメイジ魔法です。 */
public final class MageFrostBallExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "mage_frost_ball";
    static final double DEFAULT_DAMAGE_RATIO = 0.45D;
    static final double DEFAULT_FREEZE_CHANCE = 75.0D;
    static final int DEFAULT_FREEZE_DURATION_TICKS = 40;

    /** 共有発動スキルサービスで初期化します。 */
    public MageFrostBallExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "range");
        requirePositive(params, "radius");
        requirePositive(params, "damageRatio");
        requirePositive(params, "projectileSpeed");
        requirePositive(params, "projectileHitRadius");
        if (params.getInt("maxTargets", 0) < 1) {
            throw new SkillParameterException("maxTargets", "フロストボールの params[maxTargets] は1以上の整数が必要です");
        }
        if (params.getInt("freezeDurationTicks", 0) < 1) {
            throw new SkillParameterException("freezeDurationTicks", "フロストボールの凍結時間は1 tick以上が必要です");
        }
        double freezeChance = params.getDouble("freezeChance", -1.0D);
        if (freezeChance < 0.0D || freezeChance > 100.0D) {
            throw new SkillParameterException(
                    "freezeChance", "フロストボールの凍結付与確率は0以上100以下が必要です"
            );
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", 16.0D);
        double radius = params.getDouble("radius", 2.25D);
        double damageRatio = params.getDouble("damageRatio", DEFAULT_DAMAGE_RATIO);
        int maxTargets = params.getInt("maxTargets", 4);
        double projectileSpeed = params.getDouble("projectileSpeed", 1.45D);
        double projectileHitRadius = params.getDouble("projectileHitRadius", 0.45D);
        double freezeChance = params.getDouble("freezeChance", DEFAULT_FREEZE_CHANCE);
        int freezeDurationTicks = params.getInt("freezeDurationTicks", DEFAULT_FREEZE_DURATION_TICKS);
        ActiveSkillCondition frozen = new ActiveSkillCondition(
                ConditionType.FROZEN, freezeChance, freezeDurationTicks, 1.0D
        );
        boolean[] detonated = {false};

        context.services().projectiles().launchWithTermination(
                context.player(),
                context.eyeLocation(),
                context.direction(),
                frostBallProjectile(range, projectileSpeed, projectileHitRadius),
                (target, impact) -> detonate(
                        context, context.attacker(), impact, impact, detonated, radius, maxTargets, damageRatio, frozen
                ),
                termination -> {
                    if (termination.type() == SkillProjectileTermination.Type.BLOCK) {
                        detonate(
                                context,
                                context.attacker(),
                                termination.location(),
                                termination.effectLocation(),
                                detonated,
                                radius,
                                maxTargets,
                                damageRatio,
                                frozen
                        );
                    }
                }
        );
        return context.success();
    }

    /**
     * フロストボールの重力なし・非貫通仮想飛翔体仕様を返します。
     *
     * @param range 最大射程
     * @param speed 1 tickあたりの速度
     * @param hitRadius 命中判定半径
     * @return 水色の粉塵で氷球を表す飛翔体仕様
     */
    static @NotNull SkillProjectileSpec frostBallProjectile(double range, double speed, double hitRadius) {
        return new SkillProjectileSpec(
                range,
                speed,
                hitRadius,
                false,
                1,
                SharedParticleDefinitions.MAGE_FROST_BALL_TRAIL,
                null
        );
    }

    private void detonate(
            @NotNull PlayerActiveSkillContext context,
            @NotNull AstEntity attacker,
            @NotNull Location displayCenter,
            @NotNull Location effectCenter,
            boolean @NotNull [] detonated,
            double radius,
            int maxTargets,
            double damageRatio,
            @NotNull ActiveSkillCondition frozen
    ) {
        if (detonated[0]) {
            return;
        }
        detonated[0] = true;
        context.services().effects().point(displayCenter, SharedParticleDefinitions.MAGE_FROST_BALL_IMPACT);
        context.services().effects().point(displayCenter, SharedParticleDefinitions.SKILL_MAGE_ICE);
        context.services().targeting().inRadius(context.player(), effectCenter, radius, radius, maxTargets, true)
                .forEach(target -> context.services().combat().hit(
                        attacker, target, AttackType.MAGIC, DamageElement.ICE, damageRatio, frozen
                ));
        context.services().effects().sound(displayCenter, Sound.BLOCK_GLASS_BREAK, 0.65F, 1.45F);
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "フロストボールの params[" + key + "] は正数が必要です");
        }
    }
}
