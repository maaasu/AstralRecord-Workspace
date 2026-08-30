package io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
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

/** 視線方向へ雷撃を放ち、感電した敵へだけ一度連鎖する冒険者の基礎魔法です。 */
public final class AdventurerLightningBoltExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "adventurer_lightning_bolt";
    static final double DEFAULT_RANGE = 14.0D;
    static final double DEFAULT_DAMAGE_RATIO = 1.74D;
    static final double DEFAULT_CHAIN_RADIUS = 5.0D;
    static final double DEFAULT_CHAIN_DAMAGE_RATIO = 0.48D;
    static final int DEFAULT_MAX_CHAIN_TARGETS = 2;
    static final double DEFAULT_PROJECTILE_SPEED = 2.8D;
    static final double DEFAULT_PROJECTILE_HIT_RADIUS = 0.45D;

    /** 共有発動スキルサービスで初期化します。 */
    public AdventurerLightningBoltExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "range");
        requirePositive(params, "damageRatio");
        requirePositive(params, "chainRadius");
        requirePositive(params, "chainDamageRatio");
        requirePositive(params, "projectileSpeed");
        requirePositive(params, "projectileHitRadius");
        if (params.getInt("maxChainTargets", 0) < 1) {
            throw new SkillParameterException(
                    "maxChainTargets",
                    "ライトニングボルトの params[maxChainTargets] は1以上の整数が必要です"
            );
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", DEFAULT_RANGE);
        double damageRatio = params.getDouble("damageRatio", DEFAULT_DAMAGE_RATIO);
        double chainRadius = params.getDouble("chainRadius", DEFAULT_CHAIN_RADIUS);
        double chainDamageRatio = params.getDouble("chainDamageRatio", DEFAULT_CHAIN_DAMAGE_RATIO);
        int maxChainTargets = params.getInt("maxChainTargets", DEFAULT_MAX_CHAIN_TARGETS);
        double projectileSpeed = params.getDouble("projectileSpeed", DEFAULT_PROJECTILE_SPEED);
        double projectileHitRadius = params.getDouble(
                "projectileHitRadius", DEFAULT_PROJECTILE_HIT_RADIUS
        );
        SkillProjectileSpec projectile = new SkillProjectileSpec(
                range,
                projectileSpeed,
                projectileHitRadius,
                false,
                1,
                SharedParticleDefinitions.SKILL_MAGE_LIGHTNING,
                SharedParticleDefinitions.SKILL_MAGE_LIGHTNING
        );

        // 発動時の青白い収束を示し、飛翔体の軌跡は共通 projectile service に任せます。
        context.services().effects().point(
                context.eyeLocation(),
                SharedParticleDefinitions.SKILL_MAGE_LIGHTNING
        );
        context.services().projectiles().launch(
                context.player(),
                context.eyeLocation(),
                context.direction(),
                projectile,
                (target, impact) -> hitPrimaryAndChain(
                        context,
                        context.attacker(),
                        target,
                        impact,
                        damageRatio,
                        chainRadius,
                        chainDamageRatio,
                        maxChainTargets
                ),
                ignored -> { }
        );
        return context.success();
    }

    private void hitPrimaryAndChain(
            @NotNull PlayerActiveSkillContext context,
            @NotNull AstEntity attacker,
            @NotNull AstEntity primaryTarget,
            @NotNull Location primaryImpact,
            double damageRatio,
            double chainRadius,
            double chainDamageRatio,
            int maxChainTargets
    ) {
        context.services().combat().hit(
                attacker,
                primaryTarget,
                AttackType.MAGIC,
                DamageElement.LIGHTNING,
                damageRatio
        );
        context.services().effects().sound(
                primaryImpact,
                Sound.ENTITY_LIGHTNING_BOLT_IMPACT,
                0.75F,
                1.15F
        );

        // inRadius は Mob の距離順・UUID tie-break を返すため、全候補から感電対象だけを選びます。
        context.services().targeting()
                .inRadius(context.player(), primaryImpact, chainRadius, chainRadius, Integer.MAX_VALUE, true)
                .stream()
                .filter(target -> !target.id().equals(primaryTarget.id()))
                .filter(target -> context.services().combat().hasCondition(target, ConditionType.SHOCKED))
                .limit(maxChainTargets)
                .forEach(target -> {
                    Location chainImpact = target.location().clone().add(0.0D, 1.0D, 0.0D);
                    context.services().effects().line(
                            primaryImpact,
                            chainImpact,
                            0.22D,
                            SharedParticleDefinitions.CONDITION_SHOCKED_SPARK
                    );
                    context.services().effects().point(
                            chainImpact,
                            SharedParticleDefinitions.CONDITION_SHOCKED_SPARK
                    );
                    context.services().combat().hit(
                            attacker,
                            target,
                            AttackType.MAGIC,
                            DamageElement.LIGHTNING,
                            chainDamageRatio
                    );
                    context.services().effects().sound(
                            chainImpact,
                            Sound.ENTITY_LIGHTNING_BOLT_IMPACT,
                            0.55F,
                            1.55F
                    );
                });
    }

    /** テストと既定値の確認に使う雷撃飛翔体仕様を返します。 */
    static @NotNull SkillProjectileSpec lightningBoltProjectile() {
        return new SkillProjectileSpec(
                DEFAULT_RANGE,
                DEFAULT_PROJECTILE_SPEED,
                DEFAULT_PROJECTILE_HIT_RADIUS,
                false,
                1,
                SharedParticleDefinitions.SKILL_MAGE_LIGHTNING,
                SharedParticleDefinitions.SKILL_MAGE_LIGHTNING
        );
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "ライトニングボルトの params[" + key + "] は正数が必要です");
        }
    }
}
