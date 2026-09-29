package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 接近した敵へ冷気を付け、燃焼中なら熱衝撃を与えます。 */
public final class WizardRimeNovaExecutor extends WizardSkillSupport {
    public static final String ID = "wizard_rime_nova";

    /** @param services 共通の対象選択・戦闘・演出サービス */
    public WizardRimeNovaExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "radius", "maxTargets", "damageRatio", "burningMultiplier",
                "conditionChance", "conditionTicks");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        var center = context.player().getLocation().add(0.0D, 0.6D, 0.0D);
        ring(context, center, value(context, "radius"), SharedParticleDefinitions.WIZARD_PRISM_ICE_DUST);
        for (var target : sphere(context, center)) {
            double ratio = value(context, "damageRatio");
            if (context.services().combat().hasCondition(target, ConditionType.BURNING)) {
                ratio *= value(context, "burningMultiplier");
            }
            hit(context, target, DamageElement.ICE, ratio, condition(context, ConditionType.CHILLED));
        }
        return context.success();
    }
}
