package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 前方の敵を貫く火槍で燃焼連携を開始します。 */
public final class WizardCinderLanceExecutor extends WizardSkillSupport {
    public static final String ID = "wizard_cinder_lance";

    /** @param services 共通の対象選択・戦闘・演出サービス */
    public WizardCinderLanceExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "range", "hitRadius", "maxTargets", "damageRatio", "conditionChance", "conditionTicks");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        beam(context, SharedParticleDefinitions.WIZARD_PRISM_FIRE_DUST);
        var targets = line(context);
        afterCosts(context, () -> {
            for (var target : targets) {
                hit(context, target, DamageElement.FIRE, value(context, "damageRatio"),
                        condition(context, ConditionType.BURNING));
            }
        });
        return context.success();
    }
}
