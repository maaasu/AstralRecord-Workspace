package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.service.AirShiftSkillRuntimeService;
import org.jetbrains.annotations.NotNull;

/** implementationId {@code hunter_air_shift} のハンター上位職共用エアーシフトです。 */
public final class HunterAirShiftSkillExecutor implements SkillExecutor {
    public static final String ID = "hunter_air_shift";

    private final AirShiftSkillRuntimeService runtimeService;

    /**
     * runtime 状態サービスを受け取って executor を構築します。
     *
     * @param runtimeService エアーシフト状態サービス
     */
    public HunterAirShiftSkillExecutor(@NotNull AirShiftSkillRuntimeService runtimeService) {
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
        if (skill.getPassiveBindRequired()) {
            throw new SkillParameterException("passive.bindRequired", "false を指定してください");
        }
        if (skill.getResourceType() != SkillResourceType.ENERGY) {
            throw new SkillParameterException("resourceType", "ENERGY を指定してください");
        }
        Double resourceCost = skill.getResourceCost();
        if (resourceCost == null || Double.compare(resourceCost, 0.0D) != 0) {
            throw new SkillParameterException(
                    "resourceCost",
                    "ENGを消費しないため 0 を指定してください"
            );
        }

        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requireExact(params, "horizontalStrength", AirShiftSkillRuntimeService.HORIZONTAL_STRENGTH);
        requireExact(params, "verticalStrength", AirShiftSkillRuntimeService.VERTICAL_STRENGTH);
        if (params.requireString("triggerSound").isBlank()) {
            throw new SkillParameterException("triggerSound", "空でない sound key を指定してください");
        }
        requireNonNegativeFinite(
                params,
                "triggerSoundVolume",
                AirShiftSkillRuntimeService.DEFAULT_TRIGGER_SOUND_VOLUME
        );
        requirePositiveFinite(
                params,
                "triggerSoundPitch",
                AirShiftSkillRuntimeService.DEFAULT_TRIGGER_SOUND_PITCH
        );
    }

    private void requireExact(@NotNull SkillParamReader params, @NotNull String key, double expected) {
        if (Double.compare(params.getDouble(key, Double.NaN), expected) != 0) {
            throw new SkillParameterException(key, expected + " を指定してください");
        }
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
