package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** ガードを短い防御窓へ変え、同時に自身の攻撃を抑える技能です。 */
public final class PaladinGuardianBulwarkExecutor extends PaladinBuildSkillSupport {
    public static final String ID = "paladin_guardian_bulwark";

    /**
     * 共通リソース消費と期限付き戦闘効果を使う構えを作成します。
     * @param services 共通戦闘サービス
     */
    public PaladinGuardianBulwarkExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "durationTicks", "incomingMultiplier", "outgoingMultiplier");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        context.services().temporaryEffects().apply(context.player().getUniqueId(), ID,
                (long) value(context, "durationTicks"), value(context, "incomingMultiplier"),
                value(context, "outgoingMultiplier"), 1.0D);
        var center = context.player().getLocation();
        for (int i = 0; i < 3; i++) {
            context.services().effects().ring(center.clone().add(0.0D, 0.3D + i * 0.5D, 0.0D),
                    1.1D, 24, SharedParticleDefinitions.SKILL_PALADIN_FORTRESS_DUST);
        }
        return context.success();
    }
}
