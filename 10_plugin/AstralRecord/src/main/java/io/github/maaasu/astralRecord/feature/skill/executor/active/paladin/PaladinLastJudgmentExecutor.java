package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.combat.model.*;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.*;
import io.github.maaasu.astralRecord.feature.skill.model.*;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 有効な聖痕を見極めて追撃する裁きです。 */
public final class PaladinLastJudgmentExecutor extends PlayerActiveSkillExecutor {
    public static final String ID = "paladin_last_judgment";

    /** 共通サービスを受け取り、このスキルを初期化します。 */
    public PaladinLastJudgmentExecutor(@NotNull ActiveSkillServices services) { super(ID, services); }

    /** {@inheritDoc} */
    @Override public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        PaladinSkillSupport.area(skill, true);
        PaladinSkillSupport.bounded(skill, "damageRatio", 0.01D, 5.0D);
        PaladinSkillSupport.bounded(skill, "markedMultiplier", 1.0D, 3.0D);
    }

    /** {@inheritDoc} */
    @Override protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        for (AstEntity target : PaladinSkillSupport.targets(context)) {
            double ratio = context.params().getDouble("damageRatio", 1.0D);
            if (context.services().temporaryEffects().defenseMultiplier(target.id()) < 1.0D) {
                ratio *= context.params().getDouble("markedMultiplier", 1.6D);
            }
            context.services().combat().hit(context.attacker(), target, AttackType.MELEE, DamageElement.NONE, ratio);
        }
        PaladinSkillSupport.ring(context, SharedParticleDefinitions.PALADIN_JUDGMENT);
        return context.success();
    }
}
