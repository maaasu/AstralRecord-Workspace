package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import java.util.UUID;
import org.bukkit.Location;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 定点へ複数回落とす制圧射撃。 */
public final class SharpshooterSuppressiveRainExecutor extends SharpshooterBuildSupport {
    public static final String ID = "sharpshooter_suppressive_rain";

    /**
     * 共通サービスで構築します。
     * @param services 対象・戦闘・演出サービス
     */
    public SharpshooterSuppressiveRainExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader p = new SkillParamReader(skill.getId(), skill.getParams());
        positive(p, "radius"); count(p, "maxTargets", 1, 10); count(p, "pulseCount", 2, 6); count(p, "pulseIntervalTicks", 2, 20);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext c) {
        SkillParamReader p = c.params();
        Location center = c.services().targeting().groundTarget(c.player(), p.getDouble("range", 20.0D));
        c.services().effects().ring(center, p.getDouble("radius", 3.0D), 20,
                SharedParticleDefinitions.SHARPSHOOTER_SPREADING_AMBITION_RING);
        for (int i = 0; i < p.getInt("pulseCount", 3); i++) {
            c.services().tasks().later(c.caster().casterId(), "sharpshooter-rain:" + UUID.randomUUID(),
                    (long) i * p.getInt("pulseIntervalTicks", 8), () -> {
                        if (!c.player().isOnline() || c.player().isDead()
                                || c.player().getWorld() != center.getWorld()) return;
                        for (AstEntity target : c.services().targeting().inSphere(c.player(), center,
                                p.getDouble("radius", 3.0D), p.getInt("maxTargets", 6), true)) {
                            hit(c, target, DamageElement.NONE, p.getDouble("damageRatio", 0.7D));
                        }
                        c.services().effects().ring(center, p.getDouble("radius", 3.0D), 16,
                                SharedParticleDefinitions.SHARPSHOOTER_SPREADING_AMBITION_RING);
                    });
        }
        return c.success();
    }
}
