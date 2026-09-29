package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 直撃から周囲へ炎を広げる射撃。 */
public final class SharpshooterEmberBloomExecutor extends SharpshooterBuildSupport {
    public static final String ID = "sharpshooter_ember_bloom";

    /**
     * 共通サービスで構築します。
     * @param services 対象・戦闘・演出サービス
     */
    public SharpshooterEmberBloomExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader p = new SkillParamReader(skill.getId(), skill.getParams());
        positive(p, "radius"); count(p, "maxTargets", 1, 10); percentage(p, "conditionChance"); count(p, "conditionTicks", 1, 600);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext c) {
        SkillParamReader p = c.params();
        projectile(c, c.eyeLocation(), c.direction(), 1,
                SharedParticleDefinitions.SHARPSHOOTER_FIRE_ARROW_TRAIL, (target, impact) -> {
                    double ratio = p.getDouble("damageRatio", 1.0D);
                    hit(c, target, DamageElement.FIRE, ratio, condition(p, ConditionType.BURNING));
                    for (AstEntity nearby : c.services().targeting().inSphere(c.player(), impact,
                            p.getDouble("radius", 2.5D), p.getInt("maxTargets", 5) + 1, true).stream()
                            .filter(nearby -> !nearby.id().equals(target.id()))
                            .limit(p.getInt("maxTargets", 5)).toList()) {
                        hit(c, nearby, DamageElement.FIRE, ratio * 0.5D,
                                condition(p, ConditionType.BURNING));
                    }
                });
        return c.success();
    }
}
