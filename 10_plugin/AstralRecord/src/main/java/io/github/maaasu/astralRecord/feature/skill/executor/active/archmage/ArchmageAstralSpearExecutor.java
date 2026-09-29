package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** 照準地点の最も近い敵へ陣数で増幅する単体魔法を放ちます。 */
public final class ArchmageAstralSpearExecutor extends ArchmageStrikeSkillExecutor {
    public static final String ID = "archmage_astral_spear";

    /** @param services 共通発動サービス */
    public ArchmageAstralSpearExecutor(@NotNull ActiveSkillServices services) { super(ID, services); }

    /** {@inheritDoc} */
    @Override
    protected void strike(@NotNull PlayerActiveSkillContext context, @NotNull Location center,
                          double radius, double power, int circles) {
        List<AstEntity> targets = context.services().targeting().inRadius(center, radius, 3.0D, 1, true);
        if (targets.isEmpty()) {
            return;
        }
        AstEntity target = targets.getFirst();
        Location impact = context.services().targeting().center(target);
        context.services().effects().line(context.eyeLocation(), impact, 0.4D,
                SharedParticleDefinitions.SKILL_ASTRAL_RAY_BEAM);
        context.services().combat().hit(context.source().skill(), context.attacker(), target,
                AttackType.MAGIC, DamageElement.NONE, power * (1.0D + circles * 0.3D));
    }
}
