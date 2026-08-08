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
    private static final int FIXED_CHARGE_TICKS = 100;
    private static final double FIXED_REGEN_MULTIPLIER = 3.0D;

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
        if ((resourceType != SkillResourceType.MANA && resourceType != SkillResourceType.ENERGY)
            || !runtimeService.isEffectActive(context.player().getBukkit().getUniqueId())) {
            return 1.0D;
        }
        return readDouble(context.skill(), "regenMultiplier", FIXED_REGEN_MULTIPLIER);
    }

    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requireFixedInt(params, "chargeTicks", FIXED_CHARGE_TICKS);
        requireFixedDouble(skill, "regenMultiplier", FIXED_REGEN_MULTIPLIER);
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

    private double readDouble(@NotNull SkillDefinition skill, @NotNull String key, double defaultValue) {
        Object raw = skill.getParams().get(key);
        return raw instanceof Number number ? number.doubleValue() : defaultValue;
    }
}
