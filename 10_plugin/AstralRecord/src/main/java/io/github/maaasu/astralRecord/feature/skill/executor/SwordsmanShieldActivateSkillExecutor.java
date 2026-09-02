package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import org.jetbrains.annotations.NotNull;

/** implementationId {@code swordsman_shield_activate} のタンクシールドアクティベートパッシブです。 */
public final class SwordsmanShieldActivateSkillExecutor implements SkillExecutor {
    public static final String ID = "swordsman_shield_activate";

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
