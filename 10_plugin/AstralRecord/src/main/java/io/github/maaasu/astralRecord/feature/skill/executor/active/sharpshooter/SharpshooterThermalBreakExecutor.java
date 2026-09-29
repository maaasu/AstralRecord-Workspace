package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;
import java.util.List;

/** 火氷の状態を利用する交差射撃。 */
public final class SharpshooterThermalBreakExecutor extends SharpshooterBuildSupport {
    public static final String ID = "sharpshooter_thermal_break";

    /**
     * 共通サービスで構築します。
     * @param services 対象・戦闘・演出サービス
     */
    public SharpshooterThermalBreakExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader p = new SkillParamReader(skill.getId(), skill.getParams());
        positive(p, "statusBonusRatio");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext c) {
        SkillParamReader p = c.params();
        projectile(c, c.eyeLocation(), c.direction(), 1,
                SharedParticleDefinitions.SHARPSHOOTER_FIRE_ARROW_TRAIL,
                (target, impact) -> {
                    boolean affected = c.services().combat().hasCondition(target, ConditionType.BURNING)
                            || c.services().combat().hasCondition(target, ConditionType.FROZEN);
                    double total = p.getDouble("damageRatio", 1.0D)
                            + (affected ? p.getDouble("statusBonusRatio", 0.0D) : 0.0D);
                    c.services().combat().hit(c.source().skill(), c.attacker(), target,
                            AttackType.RANGED, List.of(
                                    new DamageComponent(DamageElement.FIRE, total * 0.5D),
                                    new DamageComponent(DamageElement.ICE, total * 0.5D)));
                });
        return c.success();
    }
}
