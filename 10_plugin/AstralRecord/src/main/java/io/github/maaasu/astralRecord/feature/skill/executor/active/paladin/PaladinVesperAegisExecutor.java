package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.combat.model.*;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.*;
import io.github.maaasu.astralRecord.feature.skill.model.*;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 自身とパーティーへ職業を問わず時限シールドを与えます。 */
public final class PaladinVesperAegisExecutor extends PlayerActiveSkillExecutor {
    public static final String ID = "paladin_vesper_aegis";

    /** 共通サービスを受け取り、このスキルを初期化します。 */
    public PaladinVesperAegisExecutor(@NotNull ActiveSkillServices services) { super(ID, services); }

    /** {@inheritDoc} */
    @Override public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        PaladinSkillSupport.area(skill, false);
        PaladinSkillSupport.bounded(skill, "wardBase", 1.0D, 10000.0D);
        PaladinSkillSupport.bounded(skill, "shieldRatio", 0.0D, 2.0D);
        PaladinSkillSupport.bounded(skill, "supportRatio", 0.0D, 5.0D);
        PaladinSkillSupport.integer(skill, "durationTicks", 600);
    }

    /** {@inheritDoc} */
    @Override protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        double amount = context.params().getDouble("wardBase", 30.0D)
                + Math.max(0.0D, context.source().statusSnapshot().getMaxValue(StatusType.MAX_SHIELD))
                    * context.params().getDouble("shieldRatio", 0.3D)
                + Math.max(0.0D, context.source().statusSnapshot().getMaxValue(StatusType.SUPPORT_POWER))
                    * context.params().getDouble("supportRatio", 0.8D);
        for (var target : context.services().targeting().partyInRadius(context.caster().player(),
                context.params().getDouble("radius", 8.0D), context.params().getDouble("height", 4.0D))) {
            if (context.services().temporaryEffects().grantWard(target.getBukkit().getUniqueId(), amount,
                    context.params().getInt("durationTicks", 160))) {
                context.services().effects().point(target.getBukkit().getLocation().add(0.0D, 1.0D, 0.0D),
                        SharedParticleDefinitions.PALADIN_WARD);
            }
        }
        PaladinSkillSupport.ring(context, SharedParticleDefinitions.PALADIN_WARD);
        return context.success();
    }
}
