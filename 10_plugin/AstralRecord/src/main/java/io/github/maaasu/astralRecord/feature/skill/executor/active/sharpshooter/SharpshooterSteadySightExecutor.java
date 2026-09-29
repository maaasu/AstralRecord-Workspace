package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 精密照準。 */
public final class SharpshooterSteadySightExecutor extends SharpshooterBuildSupport {
    public static final String ID = "sharpshooter_steady_sight";

    /**
     * 共通サービスで構築します。
     * @param services 対象・戦闘・演出サービス
     */
    public SharpshooterSteadySightExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader p = new SkillParamReader(skill.getId(), skill.getParams());
        positive(p, "bonusRatio");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext c) {
        SkillParamReader p = c.params();
        projectile(c, c.eyeLocation(), c.direction(), 1,
                SharedParticleDefinitions.SHARPSHOOTER_PHANTOM_SHOT_TRAIL,
                (target, impact) -> hit(c, target, DamageElement.NONE,
                        p.getDouble("damageRatio", 1.0D) + (inherited(c) ? p.getDouble("bonusRatio", 0.0D) : 0.0D)));
        return c.success();
    }
}
