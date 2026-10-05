package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.List;

/** 燃焼・冷気・感電の種類数を三属性の一撃へ集約します。 */
public final class WizardTriadConvergenceExecutor extends WizardSkillSupport {
    public static final String ID = "wizard_triad_convergence";

    /** @param services 共通の対象選択・戦闘・演出サービス */
    public WizardTriadConvergenceExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "range", "radius", "maxTargets", "damageRatio", "conditionBonusRatio");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        var center = context.services().targeting().groundTarget(context.player(), value(context, "range"));
        double radius = value(context, "radius");
        drawConvergence(context, center, radius);
        context.services().effects().point(center.clone().add(0.0D, 0.45D, 0.0D),
                SharedParticleDefinitions.WIZARD_ELEMENTAL_BALL_IMPACT.withCount(28));
        context.services().effects().sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.75F, 1.25F);
        context.services().effects().sound(center, Sound.BLOCK_GLASS_BREAK, 0.55F, 0.70F);
        for (var target : sphere(context, center)) {
            int count = 0;
            for (ConditionType type : List.of(ConditionType.BURNING, ConditionType.CHILLED, ConditionType.SHOCKED)) {
                if (context.services().combat().hasCondition(target, type)) count++;
            }
            double ratio = value(context, "damageRatio") * (1.0D + count * value(context, "conditionBonusRatio"));
            var result = context.services().combat().hit(context.source().skill(), context.attacker(), target, AttackType.MAGIC,
                    List.of(new DamageComponent(DamageElement.FIRE, ratio),
                            new DamageComponent(DamageElement.ICE, ratio),
                            new DamageComponent(DamageElement.LIGHTNING, ratio)));
            if (!result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)) {
                var impact = target.location().add(0.0D, 0.8D, 0.0D);
                context.services().effects().point(impact, SharedParticleDefinitions.WIZARD_ELEMENTAL_BALL_SPARK
                        .withCount(6 + count * 4));
                if (count > 0) context.services().effects().point(impact,
                        SharedParticleDefinitions.WIZARD_ELEMENTAL_BALL_RING.withCount(count * 6)
                                .withOffsets(0.35D, 0.50D, 0.35D));
            }
        }
        WizardSpellPresentation.animate(context, center, 4, frame -> {
            double waveRadius = radius * (frame + 1) / 4.0D;
            context.services().effects().ring(center, waveRadius, 32,
                    SharedParticleDefinitions.WIZARD_PRISM_FIRE_DUST);
            context.services().effects().ring(center.clone().add(0.0D, 0.20D, 0.0D), waveRadius, 32,
                    SharedParticleDefinitions.WIZARD_PRISM_ICE_DUST);
            context.services().effects().ring(center.clone().add(0.0D, 0.40D, 0.0D), waveRadius, 24,
                    SharedParticleDefinitions.WIZARD_LIGHTNING_STRIKE_CORE);
            if (frame == 2) context.services().effects().sound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.40F, 0.85F);
        });
        return context.success();
    }

    /** 三属性の渦を範囲外周から中心へ収束させ、実際の効果半径を表示します。 */
    private static void drawConvergence(PlayerActiveSkillContext context, Location center, double radius) {
        List<SharedParticleDefinition> particles = List.of(SharedParticleDefinitions.WIZARD_PRISM_FIRE_DUST,
                SharedParticleDefinitions.WIZARD_PRISM_ICE_DUST, SharedParticleDefinitions.WIZARD_LIGHTNING_STRIKE_CORE);
        for (int element = 0; element < particles.size(); element++) {
            var points = new ArrayList<Location>(40);
            for (int index = 0; index < 40; index++) {
                double progress = index / 39.0D;
                double angle = element * Math.PI * 2.0D / 3.0D + progress * Math.PI * 1.5D;
                double distance = radius * (1.0D - progress);
                points.add(center.clone().add(Math.cos(angle) * distance,
                        0.12D + progress * 1.25D, Math.sin(angle) * distance));
            }
            context.services().effects().points(center, points, particles.get(element));
        }
        context.services().effects().ring(center, radius, 48, SharedParticleDefinitions.WIZARD_ELEMENTAL_BALL_RING);
    }
}
