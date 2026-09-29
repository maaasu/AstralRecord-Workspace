package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 貫通する直線射撃。 */
public final class SharpshooterPiercingLineExecutor extends SharpshooterBuildSupport {
    public static final String ID = "sharpshooter_piercing_line";

    /**
     * 共通サービスで構築します。
     * @param services 対象・戦闘・演出サービス
     */
    public SharpshooterPiercingLineExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader p = new SkillParamReader(skill.getId(), skill.getParams());
        count(p, "maxHits", 2, 6); fraction(p, "pierceDecay");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext c) {
        SkillParamReader p = c.params();
        AtomicInteger hits = new AtomicInteger();
        projectile(c, c.eyeLocation(), c.direction(), p.getInt("maxHits", 3),
                SharedParticleDefinitions.SHARPSHOOTER_PHANTOM_SHOT_TRAIL,
                (target, impact) -> hit(c, target, DamageElement.NONE,
                        p.getDouble("damageRatio", 1.0D) * Math.pow(
                                p.getDouble("pierceDecay", 0.8D), hits.getAndIncrement())));
        return c.success();
    }
}
