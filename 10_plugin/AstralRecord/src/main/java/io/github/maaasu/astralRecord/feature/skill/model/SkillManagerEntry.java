package io.github.maaasu.astralRecord.feature.skill.model;

import org.jetbrains.annotations.NotNull;

/** スキルマネージャー一覧へ表示する習得個体です。 */
public record SkillManagerEntry(
    @NotNull LearnedSkillInstance learnedSkill,
    @NotNull SkillDefinition definition,
    boolean permitted
) {
    public @NotNull String bindingId() {
        return learnedSkill.getLearnedSkillId().toString();
    }
}
