package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 詠唱で収束した無属性の魔力を細い貫通線へ放ちます。 */
public final class WizardArcaneLanceExecutor extends WizardSkillSupport {
    public static final String ID = "wizard_arcane_lance";

    /** @param services 共通の対象選択・戦闘・演出サービス */
    public WizardArcaneLanceExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "range", "hitRadius", "maxTargets", "damageRatio");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        beam(context, SharedParticleDefinitions.WIZARD_PRISM_CORE_DUST);
        for (var target : line(context)) {
            hit(context, target, DamageElement.NONE, value(context, "damageRatio"));
        }
        return context.success();
    }
}
