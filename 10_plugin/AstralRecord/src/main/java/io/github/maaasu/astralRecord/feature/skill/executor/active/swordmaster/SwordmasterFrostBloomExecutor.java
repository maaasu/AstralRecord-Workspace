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

/** 冷気で接近を遅らせ、感電中の敵へ氷の追撃を与える氷華円舞です。 */
public final class SwordmasterFrostBloomExecutor extends SwordmasterSkillSupport {
    public static final String ID = "swordmaster_frost_bloom";

    /**
     * 共有サービスで初期化します。
     * @param services 戦闘・対象検索・有限タスクの共有サービス
     */
    public SwordmasterFrostBloomExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "radius", "maxTargets", "damageRatio", "shockBonusRatio", "conditionChance", "conditionTicks");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Location center = context.player().getLocation().add(0.0D, 0.6D, 0.0D);
        burst(context, center, value(context, "radius"), SharedParticleDefinitions.SWORDMASTER_ICE);
        context.services().effects().sound(center, Sound.BLOCK_GLASS_BREAK, 0.9F, 0.7F);
        for (AstEntity target : sphere(context, center, value(context, "radius"))) {
            boolean shocked = context.services().combat().hasCondition(target, ConditionType.SHOCKED);
            Hit first = hit(context, target, DamageElement.ICE, value(context, "damageRatio"),
                    condition(context, ConditionType.CHILLED));
            if (shocked && first.landed()) {
                burst(context, target.location().clone().add(0.0D, 1.0D, 0.0D), 0.8D,
                        SharedParticleDefinitions.SWORDMASTER_LIGHTNING);
                hit(context, target, DamageElement.ICE, value(context, "shockBonusRatio"));
            }
        }
        return context.success();
    }
}
