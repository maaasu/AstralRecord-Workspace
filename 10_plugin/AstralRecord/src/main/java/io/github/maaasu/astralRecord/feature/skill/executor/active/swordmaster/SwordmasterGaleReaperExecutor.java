package io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 敵陣を斬り抜け、直接撃破で再使用可能になる疾風抜刀です。 */
public final class SwordmasterGaleReaperExecutor extends SwordmasterSkillSupport {
    public static final String ID = "swordmaster_gale_reaper";

    /**
     * 共有サービスで初期化します。
     * @param services 戦闘・対象検索・有限タスクの共有サービス
     */
    public SwordmasterGaleReaperExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "range", "hitRadius", "maxTargets", "damageRatio");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        return dash(context, DamageElement.NONE, SharedParticleDefinitions.SWORDMASTER_SILVER);
    }
}
