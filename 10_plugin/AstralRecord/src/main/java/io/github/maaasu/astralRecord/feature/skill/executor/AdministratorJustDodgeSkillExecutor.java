package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.service.JustDodgeSkillRuntimeService;
import org.jetbrains.annotations.NotNull;

/** implementationId {@code administrator_just_dodge} の管理者専用ジャスト回避パッシブです。 */
public final class AdministratorJustDodgeSkillExecutor implements SkillExecutor {
    public static final String ID = "administrator_just_dodge";

    private final JustDodgeSkillRuntimeService runtimeService;

    /**
     * runtime 状態サービスを受け取って executor を構築します。
     *
     * @param runtimeService ジャスト回避状態サービス
     */
    public AdministratorJustDodgeSkillExecutor(@NotNull JustDodgeSkillRuntimeService runtimeService) {
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
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositiveInt(params, "invulnerabilityTicks");
        requirePositive(params, "energyRecoveryAmount");
    }

    private void requirePositiveInt(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) <= 0) {
            throw new SkillParameterException(key, "1 以上を指定してください");
        }
    }

    private void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        double value = params.getDouble(key, Double.NaN);
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new SkillParameterException(key, "正の有限値を指定してください");
        }
    }
}
