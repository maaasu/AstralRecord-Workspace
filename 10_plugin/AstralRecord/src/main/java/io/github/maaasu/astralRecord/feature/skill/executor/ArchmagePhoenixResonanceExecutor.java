package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.service.ArchmagePhoenixRuntimeService;
import org.jetbrains.annotations.NotNull;

/** バインドされた不死鳥との共鳴のライフサイクルを実行します。 */
public final class ArchmagePhoenixResonanceExecutor implements SkillExecutor {
    public static final String ID = "archmage_phoenix_resonance";
    private final ArchmagePhoenixRuntimeService runtime;

    /** @param runtime 不死鳥の召喚状態サービス */
    public ArchmagePhoenixResonanceExecutor(@NotNull ArchmagePhoenixRuntimeService runtime) {
        this.runtime = runtime;
    }

    @Override
    public @NotNull String implementationId() { return ID; }

    @Override
    public @NotNull SkillKind kind() { return SkillKind.PASSIVE; }

    @Override
    public @NotNull SkillCastResult cast(@NotNull SkillCastContext context) {
        return SkillCastResult.failure(null);
    }

    @Override
    public void onActivate(@NotNull PassiveSkillContext context) { runtime.activate(context); }

    @Override
    public void onDeactivate(@NotNull PassiveSkillContext context) { runtime.deactivate(context); }

    @Override
    public void onTick(@NotNull PassiveSkillContext context) { runtime.tick(context); }

    @Override
    public boolean requiresPassiveTick() { return true; }

    @Override
    public long passiveTickIntervalTicks() { return 1L; }

    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        if (!skill.getPassiveBindRequired() || skill.getMaxLevel() != 5) {
            throw new SkillParameterException("passive", "不死鳥との共鳴はバインド必須・最大Lv5です");
        }
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        if (params.getDouble("phoenixMax", ArchmagePhoenixRuntimeService.MAX_PHOENIX)
                != ArchmagePhoenixRuntimeService.MAX_PHOENIX
                || params.getDouble("targetRange", 50.0D) != 50.0D
                || params.getInt("despawnDelayTicks", 600) != 600) {
            throw new SkillParameterException("params", "phoenixMax=500、targetRange=50、despawnDelayTicks=600 が必要です");
        }
    }
}
