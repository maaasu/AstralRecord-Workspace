package io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 炎をまとう突進で敵を倒すと連続使用できる紅蓮疾駆です。 */
public final class SwordmasterCrimsonDriveExecutor extends SwordmasterSkillSupport {
    public static final String ID = "swordmaster_crimson_drive";

    /**
     * 共有サービスで初期化します。
     * @param services 戦闘・対象検索・有限タスクの共有サービス
     */
    public SwordmasterCrimsonDriveExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "range", "hitRadius", "maxTargets", "damageRatio", "conditionChance", "conditionTicks");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        return dash(context, DamageElement.FIRE, SharedParticleDefinitions.SWORDMASTER_FIRE,
                condition(context, ConditionType.BURNING));
    }
}
