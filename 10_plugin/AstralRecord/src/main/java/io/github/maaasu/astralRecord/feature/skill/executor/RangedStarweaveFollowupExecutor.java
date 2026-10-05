package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.service.RangedStarweaveRuntimeService;
import org.jetbrains.annotations.NotNull;

/** 弓の通常命中へ星糸の追矢をつなぐ、バインド必須パッシブです。 */
public final class RangedStarweaveFollowupExecutor implements SkillExecutor {
    public static final String ID = "archer_starweave_followup";
    private final RangedStarweaveRuntimeService runtime;

    /** @param runtime 所有者別の追矢状態を管理するサービス */
    public RangedStarweaveFollowupExecutor(@NotNull RangedStarweaveRuntimeService runtime) {
        this.runtime = runtime;
    }

    /** {@inheritDoc} */
    @Override public @NotNull String implementationId() { return ID; }

    /** {@inheritDoc} */
    @Override public @NotNull SkillKind kind() { return SkillKind.PASSIVE; }

    /** {@inheritDoc} */
    @Override public @NotNull SkillCastResult cast(@NotNull SkillCastContext context) {
        return SkillCastResult.failure(null);
    }

    /** {@inheritDoc} */
    @Override public void onActivate(@NotNull PassiveSkillContext context) { runtime.activate(context); }

    /** {@inheritDoc} */
    @Override public void onDeactivate(@NotNull PassiveSkillContext context) { runtime.deactivate(context); }

    /** {@inheritDoc} */
    @Override public void validateParams(@NotNull SkillDefinition skill) {
        if (!skill.getPassiveBindRequired() || skill.getMaxLevel() != 5) {
            throw new SkillParameterException("passive", "スターウィーバーはバインド必須・最大Lv5です");
        }
        if (skill.getCooldownTicks() != 120L) {
            throw new SkillParameterException("cooldownTicks", "追矢の再使用間隔は120tickです");
        }
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requireRange(params.getDouble("followupRatio", Double.NaN), 0.0D, 2.0D, "followupRatio");
        requireRange(params.getDouble("comboBonusRatio", Double.NaN), 0.0D, 1.0D, "comboBonusRatio");
        requireRange(params.getDouble("range", Double.NaN), 1.0D, 40.0D, "range");
        if (params.getDouble("flightTicks", Double.NaN) != 6.0D) {
            throw new SkillParameterException("flightTicks", "追矢の飛翔時間は6tickです");
        }
    }

    /**
     * 有限値で、安全なコンテンツ上限に収まっていることを確認します。
     * @param value 定義値
     * @param minimum 下限
     * @param maximum 上限
     * @param key 検証対象のパラメータ名
     * @throws SkillParameterException 値が有限でない、または範囲外の場合
     */
    private static void requireRange(double value, double minimum, double maximum, String key) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new SkillParameterException(key, minimum + "～" + maximum + "の有限値を指定してください");
        }
    }
}
