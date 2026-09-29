package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryContext;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 最大シールド投資を自身と仲間の即時回復へつなぐ聖域技能です。 */
public final class PaladinHolyReprieveExecutor extends PaladinBuildSkillSupport {
    public static final String ID = "paladin_holy_reprieve";
    private final PaladinHolyFieldRuntimeService field;
    private final PartyService parties;

    /**
     * 発動者の聖域と同一パーティーだけを参照する回復処理を作成します。
     * @param services 共通戦闘・回復サービス
     * @param field 聖域の状態
     * @param parties 対象を制限するパーティーサービス
     */
    public PaladinHolyReprieveExecutor(@NotNull ActiveSkillServices services,
                                       @NotNull PaladinHolyFieldRuntimeService field,
                                       @NotNull PartyService parties) {
        super(ID, services, "radius", "height", "healAmount", "shieldHealRatio", "fieldMultiplier");
        this.field = field;
        this.parties = parties;
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        var owner = context.player().getUniqueId();
        var party = parties.findParty(owner);
        var center = context.player().getLocation();
        double amount = value(context, "healAmount")
                + context.source().statusSnapshot().getMaxValue(StatusType.MAX_SHIELD) * value(context, "shieldHealRatio");
        if (field.isCasterWithinField(owner)) amount *= value(context, "fieldMultiplier");
        var recovery = HealthRecoveryContext.by(context.caster().player(),
                SkillPresentationUtil.plainName(context.source().skill(), ""));
        for (var target : context.services().targeting().playersInRadius(center,
                value(context, "radius"), value(context, "height"))) {
            var id = target.getBukkit().getUniqueId();
            if (!id.equals(owner) && (party == null || !party.members().contains(id))) continue;
            if (target.getStatusSnapshot().getCurrentHp() <= 0.0D) continue;
            if (!context.services().targeting().hasLineOfSight(context.eyeLocation(), target.getBukkit().getEyeLocation())) continue;
            context.services().combat().recoverHp(target, amount, recovery);
            context.services().effects().ring(target.getBukkit().getLocation().add(0.0D, 0.4D, 0.0D),
                    0.7D, 20, SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMITE_DUST);
        }
        context.services().effects().ring(center, value(context, "radius"), 40,
                SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMASH_END_ROD);
        return context.success();
    }
}
