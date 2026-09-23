package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillLevelDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.service.WizardPrismConditionRuntimeService;
import org.jetbrains.annotations.NotNull;

/** implementationId {@code wizard_prism_condition} のウィザード用パッシブです。 */
public final class WizardPrismConditionSkillExecutor implements SkillExecutor {
    public static final String ID = "wizard_prism_condition";

    private final WizardPrismConditionRuntimeService runtimeService;

    /** @param runtimeService 魔法陣の実行時状態サービス */
    public WizardPrismConditionSkillExecutor(@NotNull WizardPrismConditionRuntimeService runtimeService) {
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
        if (skill.getMaxLevel() != 3) {
            throw new SkillParameterException("maxLevel", "プリズムコンディションは maxLevel: 3 が必要です");
        }
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        if (Double.compare(params.getDouble("radius", Double.NaN), 1.0D) != 0
                || params.getInt("durationTicks", 0) != 60
                || Double.compare(params.getDouble("manaRecoveryRatio", Double.NaN), 0.03D) != 0) {
            throw new SkillParameterException("params", "半径1m、持続60tick、最大MP回復率0.03を指定してください");
        }
        for (int level = 2; level <= 3; level++) {
            int expectedLevel = level;
            SkillLevelDefinition definition = skill.getLevels().stream()
                    .filter(candidate -> candidate.getLevel() == expectedLevel)
                    .findFirst()
                    .orElseThrow(() -> new SkillParameterException("levels", "Lv." + expectedLevel + " の定義が必要です"));
            if (definition.getParamDeltas().size() != 2
                    || Double.compare(definition.getParamDeltas().getOrDefault("radius", Double.NaN), 1.0D) != 0
                    || Double.compare(definition.getParamDeltas().getOrDefault("durationTicks", Double.NaN), 60.0D) != 0) {
                throw new SkillParameterException("levels", "各レベルで半径+1m、持続+60tickが必要です");
            }
        }
    }
}
