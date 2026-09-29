package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/** 設置地点を守りながら敵Mobへ周期的に星屑を落とす陣です。 */
public final class ArchmageMeteorCircleExecutor extends ArchmageFieldSkillExecutor {
    public static final String ID = "archmage_meteor_circle";

    /** @param services 共通発動サービス */
    public ArchmageMeteorCircleExecutor(@NotNull ActiveSkillServices services) { super(ID, services, false); }

    /** {@inheritDoc} */
    @Override
    protected void pulse(@NotNull PlayerActiveSkillContext context, @NotNull Location center,
                         double radius, double power) {
        for (AstEntity target : context.services().targeting().inRadius(center, radius, 3.0D, 5, true)) {
            context.services().combat().hit(context.source().skill(), context.attacker(), target,
                    AttackType.MAGIC, DamageElement.NONE, power);
        }
    }
}
