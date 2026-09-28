package io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 一撃目の会心で二撃目が強くなる月影双斬です。 */
public final class SwordmasterCrescentDuetExecutor extends SwordmasterSkillSupport {
    public static final String ID = "swordmaster_crescent_duet";

    /**
     * 共有サービスで初期化します。
     * @param services 戦闘・対象検索・有限タスクの共有サービス
     */
    public SwordmasterCrescentDuetExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "range", "angle", "maxTargets", "damageRatio", "criticalMultiplier", "intervalTicks");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        boolean[] openingCritical = {false};
        sequence(context, 2, (int) value(context, "intervalTicks"), frame -> {
            boolean empowered = frame == 1 && openingCritical[0];
            slash(context, context.eyeLocation(), context.direction(), value(context, "range"),
                    empowered ? SharedParticleDefinitions.SWORDMASTER_GOLD : SharedParticleDefinitions.SWORDMASTER_VIOLET,
                    frame == 1);
            double ratio = value(context, "damageRatio") * (empowered ? value(context, "criticalMultiplier") : 1.0D);
            for (AstEntity target : cone(context)) {
                Hit hit = hit(context, target, DamageElement.NONE, ratio);
                if (frame == 0 && hit.critical()) openingCritical[0] = true;
            }
        });
        return context.success();
    }
}
