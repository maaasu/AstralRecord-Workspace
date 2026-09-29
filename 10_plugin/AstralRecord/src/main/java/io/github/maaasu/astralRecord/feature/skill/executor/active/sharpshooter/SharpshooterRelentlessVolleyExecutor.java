package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import java.util.UUID;
import org.bukkit.Location;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 短間隔で撃ち続ける連射技。 */
public final class SharpshooterRelentlessVolleyExecutor extends SharpshooterBuildSupport {
    public static final String ID = "sharpshooter_relentless_volley";

    /**
     * 共通サービスで構築します。
     * @param services 対象・戦闘・演出サービス
     */
    public SharpshooterRelentlessVolleyExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader p = new SkillParamReader(skill.getId(), skill.getParams());
        positive(p, "bonusRatio"); count(p, "pulseCount", 2, 6); count(p, "pulseIntervalTicks", 2, 20);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext c) {
        SkillParamReader p = c.params();
        Location start = c.eyeLocation();
        for (int i = 0; i < p.getInt("pulseCount", 4); i++) {
            c.services().tasks().later(c.caster().casterId(), "sharpshooter-volley:" + UUID.randomUUID(),
                    (long) i * p.getInt("pulseIntervalTicks", 4), () -> {
                        if (!c.player().isOnline() || c.player().isDead()
                                || c.player().getWorld() != start.getWorld()) return;
                        projectile(c, c.eyeLocation(), c.direction(), 1,
                                SharedParticleDefinitions.SHARPSHOOTER_PHANTOM_SHOT_TRAIL,
                                (target, impact) -> hit(c, target, DamageElement.NONE,
                                        p.getDouble("damageRatio", 0.7D)
                                                + (inherited(c) ? p.getDouble("bonusRatio", 0.0D) : 0.0D)));
                    });
        }
        return c.success();
    }
}
