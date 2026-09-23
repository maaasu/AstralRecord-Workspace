package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileTermination;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.hunter.HunterFadeShotExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.service.InheritanceBuffService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 状態異常中の敵を狙う散開矢と、各着弾地点の自然爆発を扱います。 */
public final class SharpshooterSpreadingAmbitionExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "sharpshooter_spreading_ambition";
    private InheritanceBuffService inheritanceBuffService;

    /**
     * 共通の発動スキルサービスで初期化します。
     *
     * @param services 発動・命中・演出サービス
     */
    public SharpshooterSpreadingAmbitionExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /**
     * 継承バフの付与先を設定します。
     *
     * @param service 継承バフサービス
     */
    public void setInheritanceBuffService(@NotNull InheritanceBuffService service) {
        this.inheritanceBuffService = service;
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        for (String key : new String[]{"range", "damageRatio", "explosionDamageRatio", "explosionRadius",
                "projectileSpeed", "projectileHitRadius"}) {
            if (!(params.getDouble(key, 0.0D) > 0.0D)) {
                throw new SkillParameterException(key, "拡散する野望の params[" + key + "] は正数が必要です");
            }
        }
        int pellets = params.getInt("pelletCount", 0);
        if (pellets < 1 || pellets > 5 || pellets % 2 == 0) {
            throw new SkillParameterException("pelletCount", "拡散する野望の矢数は1、3、5のいずれかが必要です");
        }
        if (params.getInt("maxExplosionTargets", 0) < 1) {
            throw new SkillParameterException("maxExplosionTargets", "爆発の最大対象数は1以上が必要です");
        }
        double angle = params.getDouble("spreadAngle", -1.0D);
        if (!Double.isFinite(angle) || angle < 0.0D || angle > 60.0D) {
            throw new SkillParameterException("spreadAngle", "散開全角は0以上60以下が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", 24.0D);
        double speed = params.getDouble("projectileSpeed", 1.6D);
        double hitRadius = params.getDouble("projectileHitRadius", 0.6D);
        double radius = params.getDouble("explosionRadius", 3.0D);
        double directRatio = params.getDouble("damageRatio", 2.0D);
        double splashRatio = params.getDouble("explosionDamageRatio", 1.0D);
        int maxTargets = params.getInt("maxExplosionTargets", 6);
        int pellets = params.getInt("pelletCount", 1);
        double angle = params.getDouble("spreadAngle", 20.0D);
        Vector forward = context.direction();
        Location origin = context.eyeLocation().clone().add(forward.clone().normalize().multiply(0.8D));
        SkillProjectileSpec projectile = new SkillProjectileSpec(
                range, speed, hitRadius, false, 1,
                SharedParticleDefinitions.SHARPSHOOTER_SPREADING_AMBITION_TRAIL,
                SharedParticleDefinitions.SHARPSHOOTER_SPREADING_AMBITION_IMPACT
        );
        for (Vector direction : HunterFadeShotExecutor.pelletDirections(forward, pellets, angle)) {
            boolean[] detonated = {false};
            context.services().projectiles().launchWithTermination(
                    context.player(), origin, direction, projectile,
                    (target, impact) -> detonate(context, impact, target, detonated, radius,
                            maxTargets, directRatio, splashRatio),
                    termination -> {
                        if (termination.type() == SkillProjectileTermination.Type.BLOCK) {
                            detonate(context, termination.effectLocation(), null, detonated, radius,
                                    maxTargets, directRatio, splashRatio);
                        }
                    }
            );
        }
        context.services().effects().point(origin, SharedParticleDefinitions.SHARPSHOOTER_SPREADING_AMBITION_MUZZLE);
        context.services().effects().sound(origin, Sound.ENTITY_ARROW_SHOOT, 1.25F, 0.75F);
        context.services().effects().sound(origin, Sound.BLOCK_GRASS_BREAK, 0.95F, 1.35F);
        if (inheritanceBuffService != null) {
            inheritanceBuffService.grant(context, (impact, ignored) -> {
                // 通常攻撃の散開は、同じ攻撃の5飛翔体をWeaponAttackSkillExecutorが生成する。
            });
        }
        return context.success();
    }

    /**
     * 直撃対象へ矢ダメージを与え、その対象を除いた周囲へ爆発ダメージを与えます。
     *
     * @param context 発動情報
     * @param impact 着弾地点
     * @param directTarget 矢の直撃対象。地形着弾時は null
     * @param detonated 同じ矢の重複起爆防止フラグ
     * @param radius 爆発半径
     * @param maxTargets 爆発の最大対象数
     * @param directRatio 直撃倍率
     * @param splashRatio 爆発倍率
     */
    private void detonate(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Location impact,
            @Nullable AstEntity directTarget,
            boolean @NotNull [] detonated,
            double radius,
            int maxTargets,
            double directRatio,
            double splashRatio
    ) {
        if (detonated[0]) {
            return;
        }
        detonated[0] = true;
        AstEntity attacker = context.attacker();
        if (directTarget != null) {
            hit(context, attacker, directTarget, directRatio);
        }
        context.services().targeting().inSphere(context.player(), impact, radius, maxTargets + 1, true).stream()
                .filter(target -> directTarget == null || !target.id().equals(directTarget.id()))
                .limit(maxTargets)
                .forEach(target -> hit(context, attacker, target, splashRatio));
        context.services().effects().point(impact, SharedParticleDefinitions.SHARPSHOOTER_SPREADING_AMBITION_BURST);
        context.services().effects().point(impact, SharedParticleDefinitions.SHARPSHOOTER_SPREADING_AMBITION_LEAVES);
        context.services().effects().point(impact, SharedParticleDefinitions.SHARPSHOOTER_SPREADING_AMBITION_BLOCK);
        context.services().effects().ring(impact, radius, 24,
                SharedParticleDefinitions.SHARPSHOOTER_SPREADING_AMBITION_RING);
        context.services().effects().sound(impact, Sound.ENTITY_GENERIC_EXPLODE, 0.95F, 1.3F);
        context.services().effects().sound(impact, Sound.BLOCK_GRASS_BREAK, 1.1F, 0.75F);
        context.services().effects().sound(impact, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.65F, 1.5F);
    }

    /** 状態異常が一つでもある対象への倍率を2倍にして適用します。 */
    private void hit(@NotNull PlayerActiveSkillContext context, @NotNull AstEntity attacker,
                     @NotNull AstEntity target, double ratio) {
        double resolved = context.services().combat().hasAnyCondition(target) ? ratio * 2.0D : ratio;
        context.services().combat().hit(context.source().skill(), attacker, target,
                AttackType.RANGED, DamageElement.NONE, resolved);
    }
}
