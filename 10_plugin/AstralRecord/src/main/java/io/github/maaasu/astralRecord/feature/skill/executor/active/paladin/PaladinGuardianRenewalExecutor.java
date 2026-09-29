package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryContext;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 被弾で蓄えたガードを自己回復に使い、攻撃消費との選択を作る技能です。 */
public final class PaladinGuardianRenewalExecutor extends PaladinBuildSkillSupport {
    public static final String ID = "paladin_guardian_renewal";

    /**
     * 回復阻害と回復補正を維持した自己回復処理を作成します。
     * @param services 共通戦闘・回復サービス
     */
    public PaladinGuardianRenewalExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "healHpRatio");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        context.services().combat().recoverHp(context.caster().player(),
                context.source().statusSnapshot().getMaxValue(StatusType.MAX_HEALTH) * value(context, "healHpRatio"),
                HealthRecoveryContext.by(context.caster().player(),
                        SkillPresentationUtil.plainName(context.source().skill(), "")));
        var center = context.player().getLocation();
        context.services().effects().ring(center.clone().add(0.0D, 0.25D, 0.0D), 1.2D, 28,
                SharedParticleDefinitions.SKILL_PALADIN_SHIELD_IMPACT_DUST);
        context.services().effects().ring(center.clone().add(0.0D, 1.0D, 0.0D), 0.7D, 20,
                SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMASH_END_ROD);
        return context.success();
    }
}
