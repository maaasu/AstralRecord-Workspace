package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;
import java.util.List;

/** 燃焼・冷気・感電の種類数を三属性の一撃へ集約します。 */
public final class WizardTriadConvergenceExecutor extends WizardSkillSupport {
    public static final String ID = "wizard_triad_convergence";

    /** @param services 共通の対象選択・戦闘・演出サービス */
    public WizardTriadConvergenceExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "range", "radius", "maxTargets", "damageRatio", "conditionBonusRatio");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        var center = context.services().targeting().groundTarget(context.player(), value(context, "range"));
        ring(context, center, value(context, "radius"), SharedParticleDefinitions.WIZARD_PRISM_FIRE_DUST);
        ring(context, center, value(context, "radius") * 0.8D, SharedParticleDefinitions.WIZARD_PRISM_ICE_DUST);
        ring(context, center, value(context, "radius") * 0.6D, SharedParticleDefinitions.WIZARD_LIGHTNING_STRIKE_CORE);
        for (var target : sphere(context, center)) {
            int count = 0;
            for (ConditionType type : List.of(ConditionType.BURNING, ConditionType.CHILLED, ConditionType.SHOCKED)) {
                if (context.services().combat().hasCondition(target, type)) count++;
            }
            double ratio = value(context, "damageRatio") * (1.0D + count * value(context, "conditionBonusRatio"));
            context.services().combat().hit(context.source().skill(), context.attacker(), target, AttackType.MAGIC,
                    List.of(new DamageComponent(DamageElement.FIRE, ratio),
                            new DamageComponent(DamageElement.ICE, ratio),
                            new DamageComponent(DamageElement.LIGHTNING, ratio)));
        }
        return context.success();
    }
}
