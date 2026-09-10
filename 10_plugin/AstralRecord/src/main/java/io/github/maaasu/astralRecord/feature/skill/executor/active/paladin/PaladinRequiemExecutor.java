package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.combat.model.*;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.*;
import io.github.maaasu.astralRecord.feature.skill.model.*;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 自身の時限シールドを葬り、残量を攻撃倍率へ換えます。 */
public final class PaladinRequiemExecutor extends PlayerActiveSkillExecutor {
    public static final String ID = "paladin_requiem";

    /** 共通サービスを受け取り、このスキルを初期化します。 */
    public PaladinRequiemExecutor(@NotNull ActiveSkillServices services) { super(ID, services); }

    /** {@inheritDoc} */
    @Override public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        PaladinSkillSupport.area(skill, true);
        PaladinSkillSupport.bounded(skill, "damageRatio", 0.01D, 3.0D);
        PaladinSkillSupport.bounded(skill, "wardPerRatio", 1.0D, 10000.0D);
        PaladinSkillSupport.bounded(skill, "bonusRatioCap", 0.0D, 3.0D);
    }

    /** {@inheritDoc} */
    @Override protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        var targets = PaladinSkillSupport.targets(context);
        if (targets.isEmpty()) return SkillCastResult.failure(PlayerMsgId.P_5805);
        double consumed = context.services().temporaryEffects().consumeWard(context.attacker().id());
        if (!(consumed > 0.0D)) return SkillCastResult.failure(PlayerMsgId.P_5805);
        double ratio = context.params().getDouble("damageRatio", 0.5D) + Math.min(
                context.params().getDouble("bonusRatioCap", 2.0D),
                consumed / context.params().getDouble("wardPerRatio", 100.0D));
        for (AstEntity target : targets) {
            context.services().combat().hit(context.attacker(), target, AttackType.MELEE, DamageElement.NONE, ratio);
        }
        PaladinSkillSupport.ring(context, SharedParticleDefinitions.PALADIN_REQUIEM);
        return context.success();
    }
}
