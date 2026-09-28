package io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 雷の剣閃を一直線に放ち感電を狙う雷鳴一文字です。 */
public final class SwordmasterThunderLineExecutor extends SwordmasterSkillSupport {
    public static final String ID = "swordmaster_thunder_line";

    /**
     * 共有サービスで初期化します。
     * @param services 戦闘・対象検索・有限タスクの共有サービス
     */
    public SwordmasterThunderLineExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "range", "hitRadius", "maxTargets", "damageRatio", "conditionChance", "conditionTicks");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Location origin = context.eyeLocation();
        Location end = context.services().targeting().clippedEnd(origin, context.direction(), value(context, "range"));
        trail(context, origin, end, SharedParticleDefinitions.SWORDMASTER_LIGHTNING);
        context.services().effects().sound(end, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.55F, 1.6F);
        for (AstEntity target : context.services().targeting().inLineBeforeBlock(
                context.player(), origin, context.direction(), origin.distance(end),
                value(context, "hitRadius"), (int) value(context, "maxTargets"))) {
            hit(context, target, DamageElement.LIGHTNING, value(context, "damageRatio"),
                    condition(context, ConditionType.SHOCKED));
        }
        return context.success();
    }
}
