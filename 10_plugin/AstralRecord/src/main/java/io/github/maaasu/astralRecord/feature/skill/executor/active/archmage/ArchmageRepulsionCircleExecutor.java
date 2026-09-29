package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/** 陣内の敵Mobを外周へ押し出します。 */
public final class ArchmageRepulsionCircleExecutor extends ArchmageFieldSkillExecutor {
    public static final String ID = "archmage_repulsion_circle";

    /** @param services 共通発動サービス */
    public ArchmageRepulsionCircleExecutor(@NotNull ActiveSkillServices services) { super(ID, services, false); }

    /** {@inheritDoc} */
    @Override
    protected void pulse(@NotNull PlayerActiveSkillContext context, @NotNull Location center,
                         double radius, double power) {
        for (AstEntity target : context.services().targeting().inRadius(center, radius, 2.5D, 8, true)) {
            context.services().combat().knockback(target, center, power, 0.12D);
        }
    }
}
