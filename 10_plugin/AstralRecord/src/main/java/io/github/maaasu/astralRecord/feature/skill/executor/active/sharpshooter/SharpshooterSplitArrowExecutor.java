package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import org.bukkit.util.Vector;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 三方向へ同時に放つ散開矢。 */
public final class SharpshooterSplitArrowExecutor extends SharpshooterBuildSupport {
    public static final String ID = "sharpshooter_split_arrow";

    /**
     * 共通サービスで構築します。
     * @param services 対象・戦闘・演出サービス
     */
    public SharpshooterSplitArrowExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader p = new SkillParamReader(skill.getId(), skill.getParams());
        count(p, "projectileCount", 2, 5); positive(p, "spreadAngle");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext c) {
        SkillParamReader p = c.params();
        int count = p.getInt("projectileCount", 3);
        double spread = p.getDouble("spreadAngle", 16.0D);
        for (int i = 0; i < count; i++) {
            double angle = -spread / 2.0D + spread * i / (count - 1);
            Vector direction = c.direction().rotateAroundY(Math.toRadians(angle));
            projectile(c, c.eyeLocation(), direction, 1,
                    SharedParticleDefinitions.SHARPSHOOTER_SPREADING_AMBITION_TRAIL,
                    (target, impact) -> hit(c, target, DamageElement.NONE,
                            p.getDouble("damageRatio", 0.9D)));
        }
        return c.success();
    }
}
