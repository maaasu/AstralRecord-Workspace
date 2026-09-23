package io.github.maaasu.astralRecord.feature.skill.executor.active.hunter;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryContext;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.jetbrains.annotations.NotNull;

/** 最大HPに応じて回復し、発動者へ短時間の回復強化を付与するヒールアローαです。 */
public final class ArcherHealArrowAlphaExecutor extends HunterHealArrowExecutor {

    public static final String ID = "archer_heal_arrow_alpha";

    /** 共有発動サービスで初期化します。 */
    public ArcherHealArrowAlphaExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        validateSharedParams(skill);
        requirePositive(new SkillParamReader(skill.getId(), skill.getParams()), "healPercent");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        if (!context.services().combat().applyBuff(context.caster().player(), ID)) {
            return SkillCastResult.failure(null);
        }
        return super.castPlayer(context);
    }

    /** {@inheritDoc} */
    @Override
    protected double resolveHealAmount(@NotNull AstPlayer target, @NotNull SkillParamReader params) {
        double maxHp = target.getStatusSnapshot().getMaxValue(StatusType.MAX_HEALTH);
        return maxHp * params.getDouble("healPercent", 0.0D) / 100.0D;
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull HealthRecoveryContext recoveryContext(
            @NotNull PlayerActiveSkillContext context,
            @NotNull AstPlayer target
    ) {
        HealthRecoveryContext recovery = HealthRecoveryContext.by(
                context.caster().player(),
                SkillPresentationUtil.plainName(context.source().skill(), "スキル")
        );
        return target.getBukkit().getUniqueId().equals(context.player().getUniqueId())
                ? recovery.withoutFollowUpBonus()
                : recovery;
    }
}
