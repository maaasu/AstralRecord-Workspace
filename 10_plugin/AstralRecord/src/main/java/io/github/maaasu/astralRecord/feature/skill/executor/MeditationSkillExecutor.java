package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.service.MeditationSkillRuntimeService;
import org.jetbrains.annotations.NotNull;

/**
 * implementationId {@code adventurer_meditation} の休息型パッシブスキル実装です。
 */
public final class MeditationSkillExecutor implements SkillExecutor {
    public static final String ID = "adventurer_meditation";
    private static final int FIXED_CHARGE_TICKS = 60;
    private static final double FIXED_INITIAL_REGEN_MULTIPLIER = 2.0D;
    private static final double FIXED_REGEN_MULTIPLIER_INCREMENT = 0.5D;
    private static final int FIXED_ACTIVE_DURATION_TICKS = 140;
    private static final String BUFF_PARAM = "buffId";
    private static final String BUFF_PREFIX = "buff:";

    private final MeditationSkillRuntimeService runtimeService;

    /**
     * runtime 状態サービスを受け取って executor を構築します。
     *
     * @param runtimeService メディテーション状態サービス
     */
    public MeditationSkillExecutor(@NotNull MeditationSkillRuntimeService runtimeService) {
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
        runtimeService.interrupt(context.player().getBukkit().getUniqueId());
    }

    @Override
    public void onDeactivate(@NotNull PassiveSkillContext context) {
        runtimeService.interrupt(context.player().getBukkit().getUniqueId());
    }

    @Override
    public void onTick(@NotNull PassiveSkillContext context) {
        runtimeService.tick(context);
    }

    @Override
    public boolean requiresPassiveTick() {
        return true;
    }

    @Override
    public long passiveTickIntervalTicks() {
        return 1L;
    }

    @Override
    public double passiveResourceRegenMultiplier(
        @NotNull PassiveSkillContext context,
        @NotNull SkillResourceType resourceType
    ) {
        if (resourceType != SkillResourceType.MANA && resourceType != SkillResourceType.ENERGY) {
            return 1.0D;
        }
        return runtimeService.resourceRegenMultiplier(context);
    }

    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requireFixedInt(params, "chargeTicks", FIXED_CHARGE_TICKS);
        requireFixedDouble(skill, "initialRegenMultiplier", FIXED_INITIAL_REGEN_MULTIPLIER);
        requireFixedDouble(skill, "regenMultiplierIncrement", FIXED_REGEN_MULTIPLIER_INCREMENT);
        requireFixedInt(params, "activeDurationTicks", FIXED_ACTIVE_DURATION_TICKS);
        String buffId = params.getRefId(BUFF_PARAM, BUFF_PREFIX);
        if (!ID.equals(buffId)) {
            throw new SkillParameterException(
                BUFF_PARAM,
                "メディテーションには buff:adventurer_meditation の参照が必要です"
            );
        }
        requirePositiveInt(params, "chargeParticleIntervalTicks");
        requirePositiveInt(params, "activeParticleIntervalTicks");
        requirePositiveInt(params, "activeSoundIntervalTicks");
    }

    /**
     * 指定されたパラメータが期待する固定 tick 値と一致することを検証します。
     *
     * @param params スキルパラメータ reader
     * @param key パラメータキー
     * @param expected 期待する固定値
     * @throws SkillParameterException 値が未定義、整数でない、または期待値と異なる場合
     */
    private void requireFixedInt(@NotNull SkillParamReader params, @NotNull String key, int expected) {
        if (params.getInt(key, Integer.MIN_VALUE) != expected) {
            throw new SkillParameterException(key, expected + " を指定してください");
        }
    }

    private void requireFixedDouble(@NotNull SkillDefinition skill, @NotNull String key, double expected) {
        Object raw = skill.getParams().get(key);
        if (!(raw instanceof Number number) || Double.compare(number.doubleValue(), expected) != 0) {
            throw new SkillParameterException(key, expected + " を指定してください");
        }
    }

    /**
     * 指定されたパラメータが1以上の整数であることを検証します。
     *
     * @param params スキルパラメータ reader
     * @param key パラメータキー
     * @throws SkillParameterException 値が未定義、整数でない、または1未満の場合
     */
    private void requirePositiveInt(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) <= 0) {
            throw new SkillParameterException(key, "1 以上を指定してください");
        }
    }

}
