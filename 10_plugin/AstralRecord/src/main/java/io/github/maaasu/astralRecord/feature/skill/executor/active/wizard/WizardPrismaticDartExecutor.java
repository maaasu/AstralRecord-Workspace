package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
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
        var projectile = new SkillProjectileSpec(value(context, "range"), value(context, "projectileSpeed"),
                value(context, "hitRadius"), false, 1,
                SharedParticleDefinitions.WIZARD_PRISM_CORE_DUST, SharedParticleDefinitions.MAGIC_IMPACT_DUST);
        context.services().projectiles().launchWithTermination(context.player(), context.eyeLocation(), context.direction(),
                projectile, (target, impact) -> hit(context, target, DamageElement.NONE, value(context, "damageRatio")),
                ignored -> { },
                context.services().prisms().interceptor(context, DamageElement.NONE));
        return context.success();
    }
}
