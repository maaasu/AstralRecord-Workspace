package io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 会心から一度だけ星形の範囲追撃を発生させる星砕きです。 */
public final class SwordmasterStarCleaveExecutor extends SwordmasterSkillSupport {
    public static final String ID = "swordmaster_star_cleave";

    /**
     * 共有サービスで初期化します。
     * @param services 戦闘・対象検索・有限タスクの共有サービス
     */
    public SwordmasterStarCleaveExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "range", "angle", "maxTargets", "damageRatio", "burstRatio", "burstRadius");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Location criticalCenter = null;
        slash(context, context.eyeLocation(), context.direction(), value(context, "range"),
                SharedParticleDefinitions.SWORDMASTER_GOLD, true);
        for (AstEntity target : cone(context)) {
            Location impact = target.location().clone().add(0.0D, 0.8D, 0.0D);
            if (hit(context, target, DamageElement.NONE, value(context, "damageRatio")).critical()
                    && criticalCenter == null) criticalCenter = impact;
        }
        if (criticalCenter != null) {
            Location center = criticalCenter;
            sequence(context, 1, 1, ignored -> {
                burst(context, center, value(context, "burstRadius"), SharedParticleDefinitions.SWORDMASTER_GOLD);
                context.services().effects().sound(center, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.0F, 1.5F);
                for (AstEntity target : sphere(context, center, value(context, "burstRadius"))) {
                    hit(context, target, DamageElement.NONE, value(context, "burstRatio"));
                }
            });
        }
        return context.success();
    }
}
