package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import org.jetbrains.annotations.NotNull;

/** 炎上付与成功通知から実行するバーンメテオストライクのパッシブ登録です。 */
public final class WizardBurnMeteorStrikeSkillExecutor implements SkillExecutor {
    public static final String ID = "wizard_burn_meteor_strike";

    @Override
    public @NotNull String implementationId() {
        return ID;
    }

    @Override
    public @NotNull SkillKind kind() {
        return SkillKind.PASSIVE;
    }

    @Override
    public @NotNull SkillCastResult cast(@NotNull SkillCastContext context) {
        return SkillCastResult.failure(null);
    }
}
