package io.github.maaasu.astralRecord.feature.skill.model;

import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;

/** 習得個体のレベル差分・シジル効果を適用した発動時スナップショットです。 */
public record ResolvedLearnedSkill(
    @NotNull LearnedSkillInstance learnedSkill,
    @NotNull SkillDefinition definition,
    @NotNull Map<StatusType, Double> statusBonuses,
    @NotNull Set<String> sigilIds
) {
    public ResolvedLearnedSkill {
        statusBonuses = Map.copyOf(statusBonuses);
        sigilIds = Set.copyOf(sigilIds);
    }

    public boolean hasSigil(@NotNull String sigilId) {
        return sigilIds.contains(sigilId);
    }
}
