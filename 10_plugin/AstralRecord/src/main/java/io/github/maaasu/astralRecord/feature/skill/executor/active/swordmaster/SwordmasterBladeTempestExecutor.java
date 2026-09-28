package io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/** 発動者を中心に四度の回転斬りを放つ旋風剣舞です。 */
public final class SwordmasterBladeTempestExecutor extends SwordmasterSkillSupport {
    public static final String ID = "swordmaster_blade_tempest";

    /**
     * 共有サービスで初期化します。
     * @param services 戦闘・対象検索・有限タスクの共有サービス
     */
    public SwordmasterBladeTempestExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "radius", "maxTargets", "damageRatio", "hitCount", "intervalTicks");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        sequence(context, (int) value(context, "hitCount"), (int) value(context, "intervalTicks"), frame -> {
            Location center = context.player().getLocation().add(0.0D, 0.8D, 0.0D);
            double radius = value(context, "radius");
            context.services().effects().ring(center, radius, 48, SharedParticleDefinitions.SWORDMASTER_SILVER);
            slash(context, center, context.direction().rotateAroundY(frame * Math.PI / 2.0D),
                    radius, SharedParticleDefinitions.SWORDMASTER_VIOLET, frame % 2 == 0);
            for (AstEntity target : sphere(context, center, radius)) {
                if (hit(context, target, DamageElement.NONE, value(context, "damageRatio")).critical()) {
                    burst(context, target.location().clone().add(0.0D, 0.8D, 0.0D), 0.7D,
                            SharedParticleDefinitions.SWORDMASTER_GOLD);
                }
            }
        });
        return context.success();
    }
}
