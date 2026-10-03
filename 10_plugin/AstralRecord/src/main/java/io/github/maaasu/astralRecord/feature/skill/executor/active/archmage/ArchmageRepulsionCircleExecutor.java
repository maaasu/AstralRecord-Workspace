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
        ArchmageTargetEffects feedback = new ArchmageTargetEffects(context, ArchmageTargetEffects.Style.REPULSION);
        for (AstEntity target : context.services().targeting().inRadius(context.player(), center, radius, 2.5D, 8, true)) {
            Location body = context.services().targeting().center(target);
            feedback.pushed(body, ArchmageTargetEffects.push(context, target, center, power, 0.12D));
        }
        feedback.render();
    }
}
