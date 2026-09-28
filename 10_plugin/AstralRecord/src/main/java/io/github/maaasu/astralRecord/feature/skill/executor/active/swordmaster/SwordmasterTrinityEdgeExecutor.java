package io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;
import java.util.List;

/** 火氷雷の状態異常を威力へ変える三煌終刃です。 */
public final class SwordmasterTrinityEdgeExecutor extends SwordmasterSkillSupport {
    public static final String ID = "swordmaster_trinity_edge";

    /**
     * 共有サービスで初期化します。
     * @param services 戦闘・対象検索・有限タスクの共有サービス
     */
    public SwordmasterTrinityEdgeExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "range", "angle", "maxTargets", "damageRatio", "conditionBonusRatio");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Location origin = context.eyeLocation();
        var direction = context.direction();
        slash(context, origin.clone().add(0.0D, -0.35D, 0.0D), direction, value(context, "range"),
                SharedParticleDefinitions.SWORDMASTER_FIRE, true);
        slash(context, origin, direction, value(context, "range") * 0.85D,
                SharedParticleDefinitions.SWORDMASTER_ICE, false);
        slash(context, origin.clone().add(0.0D, 0.35D, 0.0D), direction, value(context, "range") * 0.7D,
                SharedParticleDefinitions.SWORDMASTER_LIGHTNING, true);
        for (AstEntity target : cone(context)) {
            int conditions = 0;
            for (ConditionType type : List.of(ConditionType.BURNING, ConditionType.CHILLED, ConditionType.SHOCKED)) {
                if (context.services().combat().hasCondition(target, type)) conditions++;
            }
            double ratio = value(context, "damageRatio") * (1.0D + conditions * value(context, "conditionBonusRatio"));
            context.services().combat().hit(context.source().skill(), context.attacker(), target, AttackType.MELEE,
                    List.of(new DamageComponent(DamageElement.FIRE, ratio),
                            new DamageComponent(DamageElement.ICE, ratio),
                            new DamageComponent(DamageElement.LIGHTNING, ratio)));
            if (conditions > 0) burst(context, target.location().clone().add(0.0D, 0.8D, 0.0D),
                    1.2D, SharedParticleDefinitions.SWORDMASTER_GOLD);
        }
        context.services().effects().sound(origin, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.8F, 1.5F);
        return context.success();
    }
}
