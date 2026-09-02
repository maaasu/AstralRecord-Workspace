package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.service.BastionStrikeSkillRuntimeService;
import org.jetbrains.annotations.NotNull;

/** implementationId {@code swordsman_bastion_strike} のソードマン用反撃パッシブです。 */
public final class SwordsmanBastionStrikeExecutor implements SkillExecutor {
    public static final String ID = BastionStrikeSkillRuntimeService.SKILL_ID;

    private final BastionStrikeSkillRuntimeService runtimeService;

    /** バスティオンストライクの状態サービスを受け取って初期化します。 */
    public SwordsmanBastionStrikeExecutor(@NotNull BastionStrikeSkillRuntimeService runtimeService) {
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
            throw new SkillParameterException("cooldownTicks", "バスティオンストライクのクールダウンは正数が必要です");
        }
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositiveFinite(params, "range");
        requirePositiveFinite(params, "damageRatio");
        if (!params.getBoolean("consumeAllCurrentMana", false)) {
            throw new SkillParameterException(
                    "consumeAllCurrentMana",
                    "バスティオンストライクは現在MP全消費を指定してください"
            );
        }
        double levelFiveRequiredManaRatio = params.getDouble("levelFiveRequiredManaRatio", 0.0D);
        if (!(Double.isFinite(levelFiveRequiredManaRatio)
                && levelFiveRequiredManaRatio > 0.0D
                && levelFiveRequiredManaRatio <= 1.0D)) {
            throw new SkillParameterException(
                    "levelFiveRequiredManaRatio",
                    "バスティオンストライクのLv.5必要MP比率は0より大きく1以下の有限値が必要です"
            );
        }
    }

    private static void requirePositiveFinite(
            @NotNull SkillParamReader params,
            @NotNull String key
    ) {
        double value = params.getDouble(key, 0.0D);
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new SkillParameterException(key, "バスティオンストライクの params[" + key + "] は正の有限値が必要です");
        }
    }
}
