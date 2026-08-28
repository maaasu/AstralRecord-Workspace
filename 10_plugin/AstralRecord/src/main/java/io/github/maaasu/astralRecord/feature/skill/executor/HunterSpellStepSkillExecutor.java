package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.service.SpellStepSkillRuntimeService;
import org.jetbrains.annotations.NotNull;

/** implementationId {@code hunter_spell_step} のハンター用スペルステップパッシブです。 */
public final class HunterSpellStepSkillExecutor implements SkillExecutor {
    public static final String ID = "hunter_spell_step";

    private final SpellStepSkillRuntimeService runtimeService;

    /**
     * runtime 状態サービスを受け取って executor を構築します。
     *
     * @param runtimeService スペルステップ状態サービス
     */
    public HunterSpellStepSkillExecutor(@NotNull SpellStepSkillRuntimeService runtimeService) {
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
        if (params.getInt("windowTicks", Integer.MIN_VALUE)
                != SpellStepSkillRuntimeService.TRIGGER_WINDOW_TICKS) {
            throw new SkillParameterException(
                    "windowTicks",
                    SpellStepSkillRuntimeService.TRIGGER_WINDOW_TICKS + " を指定してください"
            );
        }
        if (params.requireString("triggerSound").isBlank()) {
            throw new SkillParameterException("triggerSound", "空でない sound key を指定してください");
        }
        requireNonNegativeFinite(
                params,
                "triggerSoundVolume",
                SpellStepSkillRuntimeService.DEFAULT_TRIGGER_SOUND_VOLUME
        );
        requirePositiveFinite(
                params,
                "triggerSoundPitch",
                SpellStepSkillRuntimeService.DEFAULT_TRIGGER_SOUND_PITCH
        );
    }

    private void requireNonNegativeFinite(
            @NotNull SkillParamReader params,
            @NotNull String key,
            double defaultValue
    ) {
        double value = params.getDouble(key, defaultValue);
        if (!Double.isFinite(value) || value < 0.0D) {
            throw new SkillParameterException(key, "0 以上の有限値を指定してください");
        }
    }

    private void requirePositiveFinite(
            @NotNull SkillParamReader params,
            @NotNull String key,
            double defaultValue
    ) {
        double value = params.getDouble(key, defaultValue);
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new SkillParameterException(key, "正の有限値を指定してください");
        }
    }
}
