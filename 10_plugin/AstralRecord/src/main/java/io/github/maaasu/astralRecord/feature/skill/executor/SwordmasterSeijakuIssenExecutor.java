package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.service.SeijakuIssenSkillRuntimeService;
import org.jetbrains.annotations.NotNull;

/** 静寂一閃のバインド状態を通常攻撃と被弾処理へ接続します。 */
public final class SwordmasterSeijakuIssenExecutor implements SkillExecutor {
    public static final String ID = SeijakuIssenSkillRuntimeService.SKILL_ID;

    private final SeijakuIssenSkillRuntimeService runtimeService;

    /**
     * 静寂一閃の状態サービスで初期化します。
     *
     * @param runtimeService 構えと反撃の状態サービス
     */
    public SwordmasterSeijakuIssenExecutor(@NotNull SeijakuIssenSkillRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull String implementationId() {
        return ID;
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull SkillKind kind() {
        return SkillKind.PASSIVE;
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull SkillCastResult cast(@NotNull SkillCastContext context) {
        return SkillCastResult.failure(null);
    }

    /** {@inheritDoc} */
    @Override
    public void onActivate(@NotNull PassiveSkillContext context) {
        runtimeService.activate(context);
    }

    /** {@inheritDoc} */
    @Override
    public void onDeactivate(@NotNull PassiveSkillContext context) {
        runtimeService.deactivate(context);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        int counterTicks = params.getInt("counterTicks", 0);
        if (counterTicks < 1 || counterTicks > 20) {
            throw new SkillParameterException("counterTicks", "静寂一閃の構え時間は1〜20tickが必要です");
        }
        requirePositive(params, "counterDamageRatio");
        requirePositive(params, "counterSweepRange");
        if (params.getInt("counterSweepMaxTargets", 0) < 1) {
            throw new SkillParameterException("counterSweepMaxTargets", "静寂一閃の薙ぎ払い対象数は1以上が必要です");
        }
        double maximum = params.getDouble("maxRiposteStacks", 5.0D);
        if (!Double.isFinite(maximum) || maximum < 1.0D || maximum > 10.0D || maximum != Math.rint(maximum)) {
            throw new SkillParameterException("maxRiposteStacks", "剣気の上限は1〜10の整数が必要です");
        }
        double bonus = params.getDouble("counterDamageBonusPerStack", 0.1D);
        if (!Double.isFinite(bonus) || bonus <= 0.0D || bonus > 1.0D) {
            throw new SkillParameterException("counterDamageBonusPerStack", "剣気1つの反撃強化率は0より大きく1以下が必要です");
        }
        double empowerment = params.getDouble("maxRiposteEmpowerment", 3.0D);
        if (!Double.isFinite(empowerment) || empowerment < 1.0D || empowerment > 10.0D
                || empowerment != Math.rint(empowerment)) {
            throw new SkillParameterException("maxRiposteEmpowerment", "剣気の強化上限は1〜10の整数が必要です");
        }
        double empowermentBonus = params.getDouble("counterEmpowermentBonus", 0.1D);
        if (!Double.isFinite(empowermentBonus) || empowermentBonus <= 0.0D || empowermentBonus > 1.0D) {
            throw new SkillParameterException("counterEmpowermentBonus", "剣気の強化率は0より大きく1以下が必要です");
        }
        double recovery = params.getDouble("energyRecoveryRatio", 0.0D);
        if (!Double.isFinite(recovery) || recovery <= 0.0D || recovery > 1.0D) {
            throw new SkillParameterException("energyRecoveryRatio", "静寂一閃のENG回復率は0より大きく1以下が必要です");
        }
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        double value = params.getDouble(key, 0.0D);
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new SkillParameterException(key, "静寂一閃の params[" + key + "] は正の有限値が必要です");
        }
    }
}
