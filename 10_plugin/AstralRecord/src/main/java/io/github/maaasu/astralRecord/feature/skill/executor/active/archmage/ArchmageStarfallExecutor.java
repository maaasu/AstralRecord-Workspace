package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/** 魔法陣の数に応じて範囲威力を高める星界の一撃です。 */
public final class ArchmageStarfallExecutor extends ArchmageStrikeSkillExecutor {
    public static final String ID = "archmage_starfall";

    /** @param services 共通発動サービス */
    public ArchmageStarfallExecutor(@NotNull ActiveSkillServices services) { super(ID, services); }

    /** {@inheritDoc} */
    @Override
    protected void strike(@NotNull PlayerActiveSkillContext context, @NotNull Location center,
                          double radius, double power, int circles) {
        ArchmageBuildSupport.drawCircle(context, center, radius, true);
        double ratio = power * (1.0D + circles * 0.2D);
        ArchmageTargetEffects feedback = new ArchmageTargetEffects(context, ArchmageTargetEffects.Style.STARFALL);
        for (AstEntity target : context.services().targeting().inRadius(context.player(), center, radius, 3.0D, 6, true)) {
            Location feet = target.location().clone();
            Location body = context.services().targeting().center(target);
            DamageResult result = context.services().combat().hit(context.source().skill(), context.attacker(), target,
                    AttackType.MAGIC, DamageElement.NONE, ratio);
            if (ArchmageTargetEffects.damaged(result)) {
                feedback.affected(feet, body);
            }
        }
        feedback.render();
    }
}
