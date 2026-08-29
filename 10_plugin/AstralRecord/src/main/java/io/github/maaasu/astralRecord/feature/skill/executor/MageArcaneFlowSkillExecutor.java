package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillLevelDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.service.ArcaneFlowSkillRuntimeService;
import org.jetbrains.annotations.NotNull;

/** implementationId {@code mage_arcane_flow} のメイジ用アーケインフローパッシブです。 */
public final class MageArcaneFlowSkillExecutor implements SkillExecutor {
    public static final String ID = "mage_arcane_flow";

    private final ArcaneFlowSkillRuntimeService runtimeService;

    /**
     * runtime 状態サービスを受け取って executor を構築します。
     *
     * @param runtimeService アーケインフロー状態サービス
     */
    public MageArcaneFlowSkillExecutor(@NotNull ArcaneFlowSkillRuntimeService runtimeService) {
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
        if (skill.getMaxLevel() != 5) {
            throw new SkillParameterException("maxLevel", "アーケインフローは maxLevel: 5 を指定してください");
        }

        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        double resolvedMaxReduction = params.getDouble(
                "castTimeReductionPercent",
                Double.NaN
        );
        requireFixedDouble(
            params,
            "castTimeReductionPercent",
            ArcaneFlowSkillRuntimeService.BASE_CAST_TIME_REDUCTION_PERCENT
        );
        for (int level = 2; level <= skill.getMaxLevel(); level++) {
            int currentLevel = level;
            SkillLevelDefinition levelDefinition = skill.getLevels().stream()
                    .filter(candidate -> candidate.getLevel() == currentLevel)
                    .findFirst()
                    .orElseThrow(() -> new SkillParameterException(
                            "levels",
                            "アーケインフローは Lv." + currentLevel + " の定義が必要です"
                    ));
            if (levelDefinition.getParamDeltas().size() != 1
                    || !levelDefinition.getParamDeltas().containsKey("castTimeReductionPercent")) {
                throw new SkillParameterException(
                        "levels[" + currentLevel + "].paramDeltas",
                        "castTimeReductionPercent だけを定義してください"
                );
            }
            double delta = levelDefinition.getParamDeltas().get("castTimeReductionPercent");
            if (Double.compare(delta, ArcaneFlowSkillRuntimeService.LEVEL_CAST_TIME_REDUCTION_DELTA_PERCENT) != 0) {
                throw new SkillParameterException(
                        "levels[" + currentLevel + "].paramDeltas.castTimeReductionPercent",
                        ArcaneFlowSkillRuntimeService.LEVEL_CAST_TIME_REDUCTION_DELTA_PERCENT + " を指定してください"
                );
            }
            resolvedMaxReduction += delta;
        }
        if (Double.compare(resolvedMaxReduction, ArcaneFlowSkillRuntimeService.MAX_CAST_TIME_REDUCTION_PERCENT) != 0) {
            throw new SkillParameterException(
                    "levels",
                    "Lv.5 の詠唱時間短縮率は "
                            + ArcaneFlowSkillRuntimeService.MAX_CAST_TIME_REDUCTION_PERCENT + "% になるよう定義してください"
            );
        }
    }

    private void requireFixedDouble(
            @NotNull SkillParamReader params,
            @NotNull String key,
            double expected
    ) {
        double actual = params.getDouble(key, Double.NaN);
        if (!Double.isFinite(actual) || Double.compare(actual, expected) != 0) {
            throw new SkillParameterException(key, expected + " を指定してください");
        }
    }
}
