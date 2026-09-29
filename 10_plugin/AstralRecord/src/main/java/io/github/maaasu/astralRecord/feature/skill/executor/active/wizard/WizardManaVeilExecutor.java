package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 攻勢を抑える代わりに短時間だけ自己被ダメージを軽減します。 */
public final class WizardManaVeilExecutor extends WizardSkillSupport {
    public static final String ID = "wizard_mana_veil";

    /** @param services 共通の一時効果・演出サービス */
    public WizardManaVeilExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "durationTicks", "incomingMultiplier", "outgoingMultiplier");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        context.services().temporaryEffects().apply(context.player().getUniqueId(), ID,
                (int) value(context, "durationTicks"), value(context, "incomingMultiplier"),
                value(context, "outgoingMultiplier"), 1.0D);
        ring(context, context.player().getLocation().add(0.0D, 1.0D, 0.0D),
                1.1D, SharedParticleDefinitions.WIZARD_PRISM_ICE_DUST);
        return context.success();
    }
}
