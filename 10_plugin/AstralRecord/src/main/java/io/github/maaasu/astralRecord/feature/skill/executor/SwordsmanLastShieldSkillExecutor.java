package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.service.LastShieldSkillRuntimeService;
import org.jetbrains.annotations.NotNull;

/** implementationId {@code swordsman_last_shield} のソードマン用防御パッシブです。 */
public final class SwordsmanLastShieldSkillExecutor implements SkillExecutor {
    public static final String ID = "swordsman_last_shield";

    private final LastShieldSkillRuntimeService runtimeService;

    /**
     * runtime 状態サービスを受け取って executor を構築します。
     *
     * @param runtimeService ラストシールド状態サービス
     */
    public SwordsmanLastShieldSkillExecutor(@NotNull LastShieldSkillRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

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

    @Override
    public void onActivate(@NotNull PassiveSkillContext context) {
        runtimeService.activate(context);
    }

    @Override
    public void onDeactivate(@NotNull PassiveSkillContext context) {
        runtimeService.deactivate(context);
    }

    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        if (skill.getCooldownTicks() <= 0L) {
            throw new SkillParameterException("cooldownTicks", "1 以上を指定してください");
        }
    }
}
