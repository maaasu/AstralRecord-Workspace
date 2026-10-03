package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 最初の敵から近傍へ一度ずつ連鎖し、感電を広げます。 */
public final class WizardStormChainExecutor extends WizardSkillSupport {
    public static final String ID = "wizard_storm_chain";

    /** @param services 共通の対象選択・戦闘・演出サービス */
    public WizardStormChainExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "range", "hitRadius", "maxTargets", "chainRadius", "damageRatio",
                "conditionChance", "conditionTicks");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        beam(context, SharedParticleDefinitions.WIZARD_LIGHTNING_STRIKE_CORE,
                SharedParticleDefinitions.SKILL_MAGE_LIGHTNING);
        AstEntity firstTarget = line(context).stream().findFirst().orElse(null);
        Location origin = context.eyeLocation();
        Location missedEnd = context.services().targeting().clippedEnd(
                origin, origin.getDirection(), value(context, "range"));
        afterCosts(context, () -> {
            AstEntity target = firstTarget;
            Location from = origin;
            Set<UUID> visited = new HashSet<>();
            List<WizardStormChainEffects.Impact> impacts = new ArrayList<>();
            for (int count = 0; target != null && count < (int) value(context, "maxTargets"); count++) {
                if (target.currentHealth() <= 0.0D || target.location().getWorld() != origin.getWorld()) break;
                visited.add(target.id());
                Location impact = context.services().targeting().center(target);
                DamageResult result = hit(context, target, DamageElement.LIGHTNING, value(context, "damageRatio"),
                        condition(context, ConditionType.SHOCKED));
                impacts.add(new WizardStormChainEffects.Impact(impact.clone(),
                        !result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)));
                from = impact;
                target = context.services().targeting().nearestFrom(
                        context.player(), from, value(context, "chainRadius"), visited);
            }
            WizardStormChainEffects.play(context, origin, missedEnd, impacts);
        });
        return context.success();
    }
}
