package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.combat.model.*;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.*;
import io.github.maaasu.astralRecord.feature.skill.model.*;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 時限シールドが残る仲間へ、破壊とともに失われる聖歌を与えます。 */
public final class PaladinCovenantExecutor extends PlayerActiveSkillExecutor {
    public static final String ID = "paladin_covenant";

    /** 共通サービスを受け取り、このスキルを初期化します。 */
    public PaladinCovenantExecutor(@NotNull ActiveSkillServices services) { super(ID, services); }

    /** {@inheritDoc} */
    @Override public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        PaladinSkillSupport.area(skill, false);
        PaladinSkillSupport.bounded(skill, "outgoingMultiplier", 1.0D, 1.5D);
        PaladinSkillSupport.integer(skill, "durationTicks", 600);
    }

    /** {@inheritDoc} */
    @Override protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        boolean applied = false;
        for (var target : context.services().targeting().partyInRadius(context.caster().player(),
                context.params().getDouble("radius", 8.0D), context.params().getDouble("height", 4.0D))) {
            applied |= context.services().temporaryEffects().empowerWard(target.getBukkit().getUniqueId(),
                    context.params().getDouble("outgoingMultiplier", 1.12D),
                    context.params().getInt("durationTicks", 120));
        }
        if (!applied) return SkillCastResult.failure(PlayerMsgId.P_5805);
        PaladinSkillSupport.ring(context, SharedParticleDefinitions.PALADIN_WARD);
        return context.success();
    }
}
