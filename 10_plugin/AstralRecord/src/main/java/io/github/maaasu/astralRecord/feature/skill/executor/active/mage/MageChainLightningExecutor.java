package io.github.maaasu.astralRecord.feature.skill.executor.active.mage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 視線上の敵から最大四体へ雷を連鎖させるメイジの連鎖雷です。 */
public final class MageChainLightningExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "mage_chain_lightning";
    private static final double[] RATIOS = {1.20D, 0.90D, 0.65D, 0.45D};

    /** 共有発動スキルサービスで初期化します。 */
    public MageChainLightningExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Location origin = context.eyeLocation();
        List<AstEntity> firstTargets = context.services().targeting().inLine(
                context.player(), origin, context.direction(), 12.0D, 0.65D, 1);
        if (firstTargets.isEmpty()) {
            Location end = context.services().targeting().clippedEnd(origin, context.direction(), 12.0D);
            context.services().effects().line(
                    origin, end, 0.45D, SharedParticleDefinitions.SKILL_MAGE_LIGHTNING);
            context.services().effects().sound(origin, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.7F, 1.4F);
            return context.success();
        }

        Set<UUID> hitIds = new HashSet<>();
        AstEntity current = firstTargets.getFirst();
        Location previous = origin;
        for (int index = 0; index < RATIOS.length && current != null; index++) {
            Location currentCenter = current.location().clone().add(0.0D, 1.0D, 0.0D);
            context.services().effects().line(
                    previous, currentCenter, 0.35D, SharedParticleDefinitions.SKILL_MAGE_LIGHTNING);
            context.services().combat().hit(
                    context.attacker(),
                    current,
                    AttackType.MAGIC,
                    DamageElement.LIGHTNING,
                    RATIOS[index],
                    new ActiveSkillCondition(ConditionType.SHOCKED, 25.0D, 40L, 1.0D)
            );
            hitIds.add(current.id());
            previous = currentCenter;
            current = context.services().targeting().nearestFrom(
                    context.player(), currentCenter, 4.5D, hitIds);
        }
        context.services().effects().sound(origin, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.85F, 1.25F);
        return context.success();
    }
}
