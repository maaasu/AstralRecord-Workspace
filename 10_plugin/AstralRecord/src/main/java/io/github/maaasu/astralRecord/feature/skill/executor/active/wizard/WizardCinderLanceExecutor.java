package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 前方の敵を貫く火槍で燃焼連携を開始します。 */
public final class WizardCinderLanceExecutor extends WizardSkillSupport {
    public static final String ID = "wizard_cinder_lance";

    /** @param services 共通の対象選択・戦闘・演出サービス */
    public WizardCinderLanceExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "range", "hitRadius", "maxTargets", "damageRatio", "conditionChance", "conditionTicks");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        var origin = context.eyeLocation();
        var end = context.services().targeting().clippedEnd(origin, context.direction(), value(context, "range"));
        WizardSpellPresentation.viewRing(context, origin.clone().add(context.direction().multiply(0.35D)),
                context.direction(), 0.48D, SharedParticleDefinitions.WIZARD_METEOR_SIGIL_RUNE);
        WizardSpellPresentation.lanceSpiral(context, origin, end, value(context, "hitRadius") * 0.75D,
                SharedParticleDefinitions.WIZARD_PRISM_FIRE_DUST);
        context.services().effects().line(origin, end, 0.30D, SharedParticleDefinitions.WIZARD_METEOR_SIGIL_RING);
        context.services().effects().sound(origin, Sound.ENTITY_BLAZE_SHOOT, 0.70F, 0.80F);
        var targets = line(context);
        afterCosts(context, () -> {
            for (var target : targets) {
                var result = hit(context, target, DamageElement.FIRE, value(context, "damageRatio"),
                        condition(context, ConditionType.BURNING));
                if (!result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)) {
                    var impact = target.location().add(0.0D, 0.8D, 0.0D);
                    context.services().effects().point(impact, SharedParticleDefinitions.MAGE_FIREBALL_IMPACT);
                    context.services().effects().point(impact,
                            SharedParticleDefinitions.WIZARD_METEOR_TRAIL_FLAME.withCount(10));
                    context.services().effects().sound(impact, Sound.BLOCK_FIRE_EXTINGUISH, 0.40F, 1.55F);
                }
            }
        });
        WizardSpellPresentation.animate(context, end, 3, frame ->
                context.services().effects().ring(end, 0.25D + frame * 0.15D, 16,
                        SharedParticleDefinitions.WIZARD_METEOR_SIGIL_RUNE));
        return context.success();
    }
}
