package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** シールド破壊を狙う針矢。 */
public final class SharpshooterShieldNeedleExecutor extends SharpshooterBuildSupport {
    public static final String ID = "sharpshooter_shield_needle";

    /**
     * 共通サービスで構築します。
     * @param services 対象・戦闘・演出サービス
     */
    public SharpshooterShieldNeedleExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader p = new SkillParamReader(skill.getId(), skill.getParams());
        positive(p, "shieldBreakMultiplier");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext c) {
        SkillParamReader p = c.params();
        projectile(c, c.eyeLocation(), c.direction(), 1,
                SharedParticleDefinitions.SHARPSHOOTER_PHANTOM_SHOT_TRAIL,
                (target, impact) -> c.services().combat().hit(c.attacker(), target,
                        AttackType.RANGED, DamageElement.NONE, p.getDouble("damageRatio", 1.0D),
                        p.getDouble("shieldBreakMultiplier", 2.5D)));
        return c.success();
    }
}
