package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/** 陣内の敵Mobを外周へ押し出します。 */
public final class ArchmageRepulsionCircleExecutor extends ArchmageFieldSkillExecutor {
    public static final String ID = "archmage_repulsion_circle";

    /** @param services 共通発動サービス */
    public ArchmageRepulsionCircleExecutor(@NotNull ActiveSkillServices services) { super(ID, services, ArchmageConstellationEffects.FieldStyle.REPULSION); }

    /** {@inheritDoc} */
    @Override
    protected void pulse(@NotNull PlayerActiveSkillContext context, @NotNull Location center,
                         double radius, double power, ArchmageConstellationEffects.FieldFeedback feedback) {
        for (AstEntity target : context.services().targeting().inRadius(context.player(), center, radius, 2.5D, 8, true)) {
            var entityId = target.mob() == null ? null : target.mob().bukkitEntityId();
            var entity = entityId == null ? null : Bukkit.getEntity(entityId);
            var before = entity == null ? null : entity.getVelocity();
            context.services().combat().knockback(target, center, power, 0.12D);
            if (entity != null && entity.getVelocity().distanceSquared(before) > 1.0E-8D) {
                feedback.accept(target.location());
            }
        }
    }
}
