package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.*;
import io.github.maaasu.astralRecord.feature.skill.service.MeleeDualCrestRuntimeService;
import org.jetbrains.annotations.NotNull;

/** デュアルクレストのバインドと命中蓄積を専用runtimeへ接続します。 */
public final class MeleeDualCrestExecutor implements SkillExecutor {
    public static final String ID = "melee_dual_crest";
    private final MeleeDualCrestRuntimeService runtime;

    /** @param runtime 同期スレッドで使用する近接印の管理サービス */
    public MeleeDualCrestExecutor(@NotNull MeleeDualCrestRuntimeService runtime) {
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
    @Override public void onTick(@NotNull PassiveSkillContext context) { runtime.tick(context); }
    /** {@inheritDoc} */
    @Override public boolean requiresPassiveTick() { return true; }
    /** {@inheritDoc} */
    @Override public long passiveTickIntervalTicks() { return 20L; }

    /** {@inheritDoc} */
    @Override public void validateParams(@NotNull SkillDefinition skill) {
        if (!skill.getPassiveBindRequired() || skill.getMaxLevel() != 5
                || skill.getResourceType() != SkillResourceType.ENERGY
                || skill.getResourceCost() == null || skill.getResourceCost() != 2.0D
                || skill.getCooldownTicks() != 80L) {
            throw new SkillParameterException("passive", "デュアルクレストはバインド必須・最大Lv5・ENG2・CD80tickです");
        }
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requireRange(params, "damageRatio", 0.35D, 0.55D, false);
        requireRange(params, "hitThreshold", 3.0D, 3.0D, true);
        requireRange(params, "hitIntervalTicks", 10.0D, 10.0D, true);
        requireRange(params, "crestDurationTicks", 120.0D, 120.0D, true);
        requireRange(params, "targetRange", 7.0D, 7.0D, false);
    }

    /** 必須値の有限性・範囲・整数契約を検証し、不正なら登録を拒否します。 */
    private static void requireRange(SkillParamReader params, String key, double minimum,
                                     double maximum, boolean integer) {
        double value = params.getDouble(key, Double.NaN);
        if (!Double.isFinite(value) || value < minimum || value > maximum
                || integer && value != Math.rint(value)) {
            throw new SkillParameterException(key, "デュアルクレストの必須値が許容範囲外です");
        }
    }
}
