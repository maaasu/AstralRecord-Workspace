package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.combat.model.*;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.*;
import io.github.maaasu.astralRecord.feature.skill.model.*;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 攻撃を捨てて敵を引き受け、通常シールドを立て直します。 */
public final class PaladinBlackSanctumExecutor extends PlayerActiveSkillExecutor {
    public static final String ID = "paladin_black_sanctum";

    /** 共通サービスを受け取り、このスキルを初期化します。 */
    public PaladinBlackSanctumExecutor(@NotNull ActiveSkillServices services) { super(ID, services); }

    /** {@inheritDoc} */
    @Override public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        PaladinSkillSupport.area(skill, true);
        PaladinSkillSupport.bounded(skill, "incomingMultiplier", 0.1D, 1.0D);
        PaladinSkillSupport.bounded(skill, "outgoingMultiplier", 0.1D, 1.0D);
        PaladinSkillSupport.bounded(skill, "shieldRecoveryRatio", 0.0D, 1.0D);
        PaladinSkillSupport.integer(skill, "durationTicks", 600);
    }

    /** {@inheritDoc} */
    @Override protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        long duration = context.params().getInt("durationTicks", 120);
        context.services().temporaryEffects().apply(context.attacker().id(), ID, duration,
                context.params().getDouble("incomingMultiplier", 0.65D),
                context.params().getDouble("outgoingMultiplier", 0.65D), 0.0D);
        context.services().combat().recoverShield(context.attacker(),
                context.source().statusSnapshot().getMaxValue(StatusType.MAX_SHIELD)
                * context.params().getDouble("shieldRecoveryRatio", 0.15D));
        for (AstEntity target : context.services().targeting().inRadius(context.player(), context.player().getLocation(),
                context.params().getDouble("radius", 5.0D), context.params().getDouble("height", 3.0D),
                context.params().getInt("maxTargets", 8), true)) {
            context.services().combat().taunt(context.attacker(), target, duration);
        }
        PaladinSkillSupport.ring(context, SharedParticleDefinitions.PALADIN_REQUIEM);
        return context.success();
    }
}
