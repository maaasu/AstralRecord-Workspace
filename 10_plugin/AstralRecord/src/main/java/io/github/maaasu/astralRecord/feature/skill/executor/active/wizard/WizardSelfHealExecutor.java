package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryContext;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 最大HPに応じて発動者本人を回復するウィザードの魔法です。 */
public final class WizardSelfHealExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "wizard_self_heal";

    /** 共有発動スキルサービスで初期化します。 */
    public WizardSelfHealExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        double healPercent = new SkillParamReader(skill.getId(), skill.getParams())
                .getDouble("healPercent", Double.NaN);
        if (!Double.isFinite(healPercent) || healPercent <= 0.0D || healPercent > 100.0D) {
            throw new SkillParameterException("healPercent", "セルフヒールの回復率は0より大きく100以下が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        double healPercent = context.params().getDouble("healPercent", 0.0D);
        double maxHp = context.caster().player().getStatusSnapshot().getMaxValue(StatusType.MAX_HEALTH);
        context.services().effects().point(
                context.player().getLocation().add(0.0D, 1.0D, 0.0D),
                SharedParticleDefinitions.MAGE_HEAL_AURA_PULSE
        );
        double recovered = context.services().combat().recoverHp(
                context.caster().player(),
                maxHp * healPercent / 100.0D,
                HealthRecoveryContext.by(
                        context.caster().player(),
                        SkillPresentationUtil.plainName(context.source().skill(), "スキル")
                ).withoutFollowUpBonus()
        );
        if (recovered > 0.0D) {
            context.services().effects().point(
                    context.player().getLocation().add(0.0D, 1.0D, 0.0D),
                    SharedParticleDefinitions.MAGE_HEAL_AURA_HEAL
            );
        }
        return context.success();
    }
}
