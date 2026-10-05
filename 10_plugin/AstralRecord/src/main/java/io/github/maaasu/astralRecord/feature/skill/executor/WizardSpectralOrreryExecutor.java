package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.service.WizardSpectralOrreryRuntimeService;
import org.jetbrains.annotations.NotNull;

/** 装備とバインドで有効になる三属性連携の天球儀を登録します。 */
public final class WizardSpectralOrreryExecutor implements SkillExecutor {
    public static final String ID = "wizard_spectral_orrery";
    private final WizardSpectralOrreryRuntimeService runtime;

    /** @param runtime 連携状態と追撃を管理するサービス */
    public WizardSpectralOrreryExecutor(@NotNull WizardSpectralOrreryRuntimeService runtime) {
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
            throw new SkillParameterException("passive", "天球儀はバインド必須・最大Lv5です");
        }
        WizardSpectralOrreryRuntimeService.readParameters(skill);
    }
}
