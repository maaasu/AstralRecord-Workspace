package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

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

/** 多色の魔力球を飛ばし、状態異常中の敵への着弾ダメージを倍増します。 */
public final class WizardElementalBallExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "wizard_elemental_ball";

    /**
     * 共有発動スキルサービスで初期化します。
     *
     * @param services 発射体、対象選択、ダメージ、演出の共有サービス
     */
    public WizardElementalBallExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        for (String key : new String[]{"range", "radius", "damageRatio", "projectileSpeed", "projectileHitRadius"}) {
            requirePositive(params, key);
        }
        if (params.getInt("maxTargets", 0) < 1) {
            throw new SkillParameterException("maxTargets", "エレメンタルボールの最大対象数は1以上が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", 16.0D);
        double radius = params.getDouble("radius", 2.25D);
        double damageRatio = params.getDouble("damageRatio", 2.45D);
        int maxTargets = params.getInt("maxTargets", 4);
        double projectileSpeed = params.getDouble("projectileSpeed", 1.45D);
        double projectileHitRadius = params.getDouble("projectileHitRadius", 0.45D);
        boolean[] detonated = {false};

        context.services().projectiles().launchWithTermination(
                context.player(),
                context.eyeLocation(),
                context.direction(),
                projectile(range, projectileSpeed, projectileHitRadius),
                (target, impact) -> detonate(context, impact, impact, detonated, radius, maxTargets, damageRatio),
                termination -> {
                    if (termination.type() == SkillProjectileTermination.Type.BLOCK) {
                        detonate(context, termination.location(), termination.effectLocation(),
                                detonated, radius, maxTargets, damageRatio);
                    }
                },
                context.services().prisms().interceptor(context, DamageElement.NONE)
        );
        context.services().effects().sound(context.eyeLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9F, 1.25F);
        return context.success();
    }

    /**
     * 非貫通の魔力球と多色の軌跡を定義します。
     *
     * @param range 最大射程
     * @param speed 1 tickあたりの飛翔距離
     * @param hitRadius 命中判定半径
     * @return 多色粒子を伴う仮想飛翔体仕様
     */
    private static @NotNull SkillProjectileSpec projectile(double range, double speed, double hitRadius) {
        return new SkillProjectileSpec(
                range, speed, hitRadius, false, 1,
                SharedParticleDefinitions.WIZARD_ELEMENTAL_BALL_TRAIL, null
        );
    }

    /**
     * 最初の敵またはブロックへの着弾地点を一度だけ爆発させます。
     *
     * @param context 発動情報
     * @param displayCenter 演出の中心
     * @param effectCenter 攻撃対象を探す中心
     * @param detonated 重複起爆を防ぐ状態
     * @param radius 着弾範囲の半径
     * @param maxTargets 対象数の上限
     * @param damageRatio 状態異常による倍化前のダメージ倍率
     */
    private void detonate(
            @NotNull PlayerActiveSkillContext context,
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
        context.services().effects().point(displayCenter, SharedParticleDefinitions.WIZARD_ELEMENTAL_BALL_IMPACT);
        context.services().effects().point(displayCenter, SharedParticleDefinitions.WIZARD_ELEMENTAL_BALL_FLAME);
        context.services().effects().point(displayCenter, SharedParticleDefinitions.WIZARD_ELEMENTAL_BALL_FROST);
        context.services().effects().point(displayCenter, SharedParticleDefinitions.WIZARD_ELEMENTAL_BALL_SPARK);
        context.services().effects().ring(displayCenter, radius, 24,
                SharedParticleDefinitions.WIZARD_ELEMENTAL_BALL_RING);
        AstEntity attacker = context.attacker();
        context.services().targeting().inRadius(context.player(), effectCenter, radius, radius, maxTargets, true)
                .forEach(target -> hit(context, attacker, target, damageRatio));
        context.services().effects().sound(displayCenter, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.8F, 1.35F);
    }

    /**
     * 対象の状態異常を命中直前に判定し、条件成立時の倍率を2倍にします。
     *
     * @param context 発動情報
     * @param attacker 攻撃者
     * @param target 命中対象
     * @param damageRatio 条件成立前の倍率
     */
    private void hit(@NotNull PlayerActiveSkillContext context, @NotNull AstEntity attacker,
                     @NotNull AstEntity target, double damageRatio) {
        double resolvedRatio = context.services().combat().hasAnyCondition(target)
                ? damageRatio * 2.0D : damageRatio;
        context.services().combat().hit(context.source().skill(), attacker, target,
                AttackType.MAGIC, DamageElement.NONE, resolvedRatio);
    }

    /**
     * 必須の正数パラメータを検証します。
     *
     * @param params スキルのパラメータ
     * @param key 検証するパラメータ名
     */
    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "エレメンタルボールの params[" + key + "] は正数が必要です");
        }
    }
}
