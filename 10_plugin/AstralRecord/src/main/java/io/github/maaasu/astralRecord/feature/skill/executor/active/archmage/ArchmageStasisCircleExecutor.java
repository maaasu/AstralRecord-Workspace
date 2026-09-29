package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/** 陣内の敵Mobの移動速度を周期的に落とします。 */
public final class ArchmageStasisCircleExecutor extends ArchmageFieldSkillExecutor {
    public static final String ID = "archmage_stasis_circle";

    /** @param services 共通発動サービス */
    public ArchmageStasisCircleExecutor(@NotNull ActiveSkillServices services) { super(ID, services, false); }

    /** {@inheritDoc} */
    @Override
    protected void pulse(@NotNull PlayerActiveSkillContext context, @NotNull Location center,
                         double radius, double power) {
        for (AstEntity target : context.services().targeting().inRadius(center, radius, 2.5D, 8, true)) {
            double speed = target.statValue(StatusType.MOVEMENT_SPEED);
            context.services().combat().applyTemporaryMovementSpeedReduction(
                    target, (speed > 0.0D ? speed : 100.0D) * power, 30L);
        }
    }
}
