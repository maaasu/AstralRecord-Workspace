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

/** 足元に残した焔の花で三度斬り上げる焔花咲きです。 */
public final class SwordmasterFlameLotusExecutor extends SwordmasterSkillSupport {
    public static final String ID = "swordmaster_flame_lotus";

    /**
     * 共有サービスで初期化します。
     * @param services 戦闘・対象検索・有限タスクの共有サービス
     */
    public SwordmasterFlameLotusExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "radius", "maxTargets", "damageRatio", "hitCount", "intervalTicks", "conditionChance", "conditionTicks");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Location center = context.player().getLocation().add(0.0D, 0.2D, 0.0D);
        context.services().effects().ring(center, value(context, "radius"), 40, SharedParticleDefinitions.SWORDMASTER_FIRE);
        sequence(context, (int) value(context, "hitCount"), (int) value(context, "intervalTicks"), frame -> {
            burst(context, center.clone().add(0.0D, 0.3D + frame * 0.25D, 0.0D),
                    value(context, "radius"), SharedParticleDefinitions.SWORDMASTER_FIRE);
            context.services().effects().sound(center, Sound.ITEM_FIRECHARGE_USE, 0.65F, 0.85F + frame * 0.2F);
            for (AstEntity target : sphere(context, center, value(context, "radius"))) {
                hit(context, target, DamageElement.FIRE, value(context, "damageRatio"),
                        condition(context, ConditionType.BURNING));
            }
        });
        return context.success();
    }
}
