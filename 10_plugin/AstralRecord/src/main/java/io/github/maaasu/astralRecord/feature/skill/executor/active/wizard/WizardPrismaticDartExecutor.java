package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 単体へ直撃させるかプリズムへ吸収させるかを選ぶ低消費弾です。 */
public final class WizardPrismaticDartExecutor extends WizardSkillSupport {
    public static final String ID = "wizard_prismatic_dart";

    /** @param services 共通の飛翔体・戦闘・プリズムサービス */
    public WizardPrismaticDartExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "range", "damageRatio", "projectileSpeed", "hitRadius");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        var muzzle = context.eyeLocation();
        WizardSpellPresentation.viewRing(context, muzzle, context.direction(), 0.40D,
                SharedParticleDefinitions.WIZARD_PRISM_CORE_DUST);
        WizardSpellPresentation.viewRing(context, muzzle.clone().add(context.direction().multiply(0.25D)),
                context.direction(), 0.23D, SharedParticleDefinitions.WIZARD_ELEMENTAL_BALL_RING);
        var projectile = new SkillProjectileSpec(value(context, "range"), value(context, "projectileSpeed"),
                value(context, "hitRadius"), false, 1,
                SharedParticleDefinitions.WIZARD_PRISM_CORE_DUST.withCount(3).withOffsets(0.05D, 0.05D, 0.05D),
                SharedParticleDefinitions.MAGIC_IMPACT_ENCHANT);
        context.services().projectiles().launchWithTermination(context.player(), context.eyeLocation(), context.direction(),
                projectile, (target, impact) -> {
                    var result = hit(context, target, DamageElement.NONE, value(context, "damageRatio"));
                    if (!result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)) {
                        WizardSpellPresentation.viewRing(context, impact, context.direction(), 0.45D,
                                SharedParticleDefinitions.WIZARD_PRISM_CORE_DUST);
                        context.services().effects().point(impact, SharedParticleDefinitions.WIZARD_ELEMENTAL_BALL_RING
                                .withCount(12).withOffsets(0.22D, 0.22D, 0.22D));
                        context.services().effects().sound(impact, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.60F, 1.55F);
                    }
                },
                ignored -> { },
                context.services().prisms().interceptor(context, DamageElement.NONE));
        return context.success();
    }
}
