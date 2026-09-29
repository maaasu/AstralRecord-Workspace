package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/** 陣に留まる仲間のENGを回復します。 */
public final class ArchmageVigorCircleExecutor extends ArchmageFieldSkillExecutor {
    public static final String ID = "archmage_vigor_circle";

    /** @param services 共通発動サービス */
    public ArchmageVigorCircleExecutor(@NotNull ActiveSkillServices services) { super(ID, services, true); }

    /** {@inheritDoc} */
    @Override
    protected void pulse(@NotNull PlayerActiveSkillContext context, @NotNull Location center,
                         double radius, double power) {
        for (AstPlayer target : context.services().targeting().playersInRadius(center, radius, 2.0D)) {
            context.services().combat().recoverEnergyByMaxRatio(target, power);
        }
    }
}
