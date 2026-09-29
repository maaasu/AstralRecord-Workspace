package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/** 範囲内の仲間へ周期的にシールドを補充する陣です。 */
public final class ArchmageAegisCircleExecutor extends ArchmageFieldSkillExecutor {
    public static final String ID = "archmage_aegis_circle";

    /** @param services 共通発動サービス */
    public ArchmageAegisCircleExecutor(@NotNull ActiveSkillServices services) { super(ID, services, true); }

    /** {@inheritDoc} */
    @Override
    protected void pulse(@NotNull PlayerActiveSkillContext context, @NotNull Location center,
                         double radius, double power) {
        for (AstPlayer target : context.services().targeting().playersInRadius(center, radius, 2.0D)) {
            double maximum = target.getStatusSnapshot().getMaxValue(StatusType.MAX_SHIELD);
            context.services().combat().recoverShield(AstEntity.player(target), maximum * power);
        }
    }
}
