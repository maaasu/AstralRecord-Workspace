package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.combat.model.*;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.*;
import io.github.maaasu.astralRecord.feature.skill.model.*;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 聖痕を刻み、味方も利用できる防御低下を残します。 */
public final class PaladinAnathemaExecutor extends PlayerActiveSkillExecutor {
    public static final String ID = "paladin_anathema";

    /** 共通サービスを受け取り、このスキルを初期化します。 */
    public PaladinAnathemaExecutor(@NotNull ActiveSkillServices services) { super(ID, services); }

    /** {@inheritDoc} */
    @Override public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        PaladinSkillSupport.area(skill, true);
        PaladinSkillSupport.bounded(skill, "damageRatio", 0.01D, 5.0D);
        PaladinSkillSupport.bounded(skill, "defenseReductionPercent", 1.0D, 50.0D);
        PaladinSkillSupport.integer(skill, "durationTicks", 600);
    }

    /** {@inheritDoc} */
    @Override protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        for (AstEntity target : PaladinSkillSupport.targets(context)) {
            DamageResult hit = context.services().combat().hit(context.attacker(), target, AttackType.MELEE,
                    DamageElement.NONE, context.params().getDouble("damageRatio", 0.6D));
            if (!hit.evaded()) {
                context.services().temporaryEffects().reduceDefense(target.id(),
                        context.params().getDouble("defenseReductionPercent", 15.0D),
                        context.params().getInt("durationTicks", 120));
            }
        }
        PaladinSkillSupport.ring(context, SharedParticleDefinitions.PALADIN_JUDGMENT);
        return context.success();
    }
}
