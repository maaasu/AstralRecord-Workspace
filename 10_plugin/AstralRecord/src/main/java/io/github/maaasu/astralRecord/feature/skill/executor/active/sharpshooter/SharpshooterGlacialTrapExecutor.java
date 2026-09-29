package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import org.bukkit.Location;
import java.util.UUID;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 遅れて凍結する地面の氷域。 */
public final class SharpshooterGlacialTrapExecutor extends SharpshooterBuildSupport {
    public static final String ID = "sharpshooter_glacial_trap";

    /**
     * 共通サービスで構築します。
     * @param services 対象・戦闘・演出サービス
     */
    public SharpshooterGlacialTrapExecutor(@NotNull ActiveSkillServices services) {
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
        Location center = c.services().targeting().groundTarget(c.player(), p.getDouble("range", 16.0D));
        c.services().effects().ring(center, p.getDouble("radius", 3.0D), 18,
                SharedParticleDefinitions.SHARPSHOOTER_ICE_ARROW_TRAIL);
        c.services().tasks().later(c.caster().casterId(), "sharpshooter-glacial:" + UUID.randomUUID(),
                12L, () -> {
                    if (!c.player().isOnline() || c.player().isDead()
                            || c.player().getWorld() != center.getWorld()) return;
                    for (AstEntity target : c.services().targeting().inSphere(c.player(), center,
                            p.getDouble("radius", 3.0D), p.getInt("maxTargets", 5), true)) {
                        hit(c, target, DamageElement.ICE, p.getDouble("damageRatio", 0.9D),
                                condition(p, ConditionType.FROZEN));
                    }
                    c.services().effects().ring(center, p.getDouble("radius", 3.0D), 20,
                            SharedParticleDefinitions.SHARPSHOOTER_ICE_ARROW_TRAIL);
                });
        return c.success();
    }
}
