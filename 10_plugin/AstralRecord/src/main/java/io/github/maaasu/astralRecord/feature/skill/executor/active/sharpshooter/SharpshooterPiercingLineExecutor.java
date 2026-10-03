package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.service.InheritanceBuffService.InheritanceImpact;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/** 貫通する直線射撃。 */
public final class SharpshooterPiercingLineExecutor extends SharpshooterBuildSupport {
    public static final String ID = "sharpshooter_piercing_line";

    /**
     * 共通サービスで構築します。
     * @param services 対象・戦闘・演出サービス
     */
    public SharpshooterPiercingLineExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader p = new SkillParamReader(skill.getId(), skill.getParams());
        count(p, "maxHits", 2, 6); fraction(p, "pierceDecay");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext c) {
        Location origin = c.eyeLocation();
        SharpshooterSignatureEffects.horizonCast(c, origin, c.direction());
        launchPiercing(c, origin, c.direction(), 1.0D, null, 0);
        grantInheritance(c, impact -> hasEffectiveMobImpact(impact)
                        && impact.direction() != null && impact.direction().lengthSquared() > 1.0E-8D,
                (impact, multiplier) -> fireInherited(c, impact, multiplier));
        return c.success();
    }

    /**
     * 通常攻撃の実際の飛行方向へ、元の命中対象を除いた貫通追撃を発射します。
     * @param context 元スキルの実行値
     * @param impact 通常攻撃の有効命中
     * @param multiplier 継承の心得のダメージ倍率
     */
    private void fireInherited(@NotNull PlayerActiveSkillContext context,
                               @NotNull InheritanceImpact impact, double multiplier) {
        Vector direction = java.util.Objects.requireNonNull(impact.direction()).clone().normalize();
        Location origin = impact.location().clone().add(direction.clone().multiply(0.1D));
        AstEntity firstTarget = java.util.Objects.requireNonNull(impact.target());
        SharpshooterSignatureEffects.horizonCast(context, impact.location(), direction);
        SharpshooterSignatureEffects.horizonPierce(context, impact.location(), 0);
        if (firstTarget.currentHealth() > 0.0D) {
            hit(context, firstTarget, DamageElement.NONE,
                    context.params().getDouble("damageRatio", 1.0D) * multiplier);
        }
        launchPiercing(context, origin, direction, multiplier, firstTarget.id(), 1);
    }

    /**
     * 元スキルの貫通数と減衰を用い、先頭の命中枠を差し引いて飛翔体を発射します。
     * @param context 元スキルの実行値
     * @param origin 発射位置
     * @param direction 発射方向
     * @param multiplier ダメージ倍率
     * @param excludedTargetId 通常攻撃で既に命中した対象。直接発動ではnull
     * @param firstHitIndex 既に適用または消費した先頭命中枠の数
     */
    private void launchPiercing(@NotNull PlayerActiveSkillContext context,
                              @NotNull Location origin, @NotNull Vector direction,
                              double multiplier, UUID excludedTargetId, int firstHitIndex) {
        SkillParamReader params = context.params();
        int maxHits = params.getInt("maxHits", 3) - firstHitIndex;
        SkillProjectileSpec spec = new SkillProjectileSpec(
                params.getDouble("range", 23.0D), params.getDouble("projectileSpeed", 2.0D),
                params.getDouble("projectileHitRadius", 0.55D), true, maxHits,
                SharedParticleDefinitions.SHARPSHOOTER_HORIZON_TRAIL,
                SharedParticleDefinitions.SHARPSHOOTER_HORIZON_IMPACT);
        AtomicInteger hits = new AtomicInteger(firstHitIndex);
        java.util.function.BiConsumer<AstEntity, Location> onHit = (target, impact) -> {
            int hitIndex = hits.getAndIncrement();
            SharpshooterSignatureEffects.horizonPierce(context, impact, hitIndex);
            hit(context, target, DamageElement.NONE,
                    params.getDouble("damageRatio", 1.0D) * multiplier
                            * Math.pow(params.getDouble("pierceDecay", 0.8D), hitIndex));
        };
        if (excludedTargetId == null) {
            context.services().projectiles().launch(context.player(), origin, direction, spec,
                    onHit, ignored -> { });
        } else {
            context.services().projectiles().launchExcludingTarget(context.player(), origin,
                    excludedTargetId, direction, spec, onHit, ignored -> { });
        }
    }
}
