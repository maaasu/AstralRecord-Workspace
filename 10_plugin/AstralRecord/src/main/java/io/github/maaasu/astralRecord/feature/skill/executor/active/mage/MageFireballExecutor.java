package io.github.maaasu.astralRecord.feature.skill.executor.active.mage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
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

/** 火球を飛ばし、最初の着弾地点へ小規模な火属性範囲攻撃を行うメイジの初期魔法です。 */
public final class MageFireballExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "mage_fireball";

    /** 共有発動スキルサービスで初期化します。 */
    public MageFireballExecutor(@NotNull ActiveSkillServices services) {
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
            throw new SkillParameterException("maxTargets", "ファイアーボールの params[maxTargets] は1以上の整数が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", 16.0D);
        double radius = params.getDouble("radius", 2.25D);
        double damageRatio = params.getDouble("damageRatio", 1.65D);
        int maxTargets = params.getInt("maxTargets", 4);
        double projectileSpeed = params.getDouble("projectileSpeed", 1.45D);
        double projectileHitRadius = params.getDouble("projectileHitRadius", 0.45D);
        boolean[] detonated = {false};

        context.services().projectiles().launchWithTermination(
                context.player(),
                context.eyeLocation(),
                context.direction(),
                fireballProjectile(range, projectileSpeed, projectileHitRadius),
                (target, impact) -> detonate(
                        context, context.attacker(), impact, impact, detonated, radius, maxTargets, damageRatio
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
                                damageRatio
                        );
                    }
                }
        );
        context.services().effects().sound(context.eyeLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.0F, 1.05F);
        return context.success();
    }

    /**
     * ファイアーボールの重力なし・非貫通仮想飛翔体仕様を返します。
     *
     * @param range 最大射程
     * @param speed 1 tickあたりの速度
     * @param hitRadius 命中判定半径
     * @return フレイムラッシュ由来の橙色粉塵で球体を表す飛翔体仕様
     */
    static @NotNull SkillProjectileSpec fireballProjectile(double range, double speed, double hitRadius) {
        return new SkillProjectileSpec(
                range,
                speed,
                hitRadius,
                false,
                1,
                SharedParticleDefinitions.MAGE_FIREBALL_TRAIL,
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
            double damageRatio
    ) {
        if (detonated[0]) {
            return;
        }
        detonated[0] = true;
        context.services().effects().point(displayCenter, SharedParticleDefinitions.MAGE_FIREBALL_IMPACT);
        context.services().effects().point(displayCenter, SharedParticleDefinitions.SKILL_MAGE_FIRE);
        context.services().targeting().inRadius(context.player(), effectCenter, radius, radius, maxTargets, true)
                .forEach(target -> context.services().combat().hit(
                        attacker, target, AttackType.MAGIC, DamageElement.FIRE, damageRatio
                ));
        context.services().effects().sound(displayCenter, Sound.ENTITY_GENERIC_EXPLODE, 0.50F, 1.55F);
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "ファイアーボールの params[" + key + "] は正数が必要です");
        }
    }
}
