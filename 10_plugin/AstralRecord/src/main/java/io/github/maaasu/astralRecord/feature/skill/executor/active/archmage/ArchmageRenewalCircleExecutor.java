package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryContext;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/** 仲間が陣に留まる間、HPを短周期で回復します。 */
public final class ArchmageRenewalCircleExecutor extends ArchmageFieldSkillExecutor {
    public static final String ID = "archmage_renewal_circle";

    /** @param services 共通発動サービス */
    public ArchmageRenewalCircleExecutor(@NotNull ActiveSkillServices services) { super(ID, services, true); }

    /** {@inheritDoc} */
    @Override
    protected void pulse(@NotNull PlayerActiveSkillContext context, @NotNull Location center,
                         double radius, double power) {
        HealthRecoveryContext source = HealthRecoveryContext.by(context.caster().player(),
                SkillPresentationUtil.plainName(context.source().skill(), "スキル"));
        for (AstPlayer target : context.services().targeting().playersInRadius(center, radius, 2.0D)) {
            double maximum = target.getStatusSnapshot().getMaxValue(StatusType.MAX_HEALTH);
            context.services().combat().recoverHp(target, maximum * power, source);
        }
    }
}
