package io.github.maaasu.astralRecord.feature.skill.model;

import org.jetbrains.annotations.NotNull;

/** スキルマネージャー一覧へ表示する習得個体です。 */
public record SkillManagerEntry(
    @NotNull LearnedSkillInstance learnedSkill,
    @NotNull SkillDefinition definition,
    boolean permitted,
    @NotNull ResolvedLearnedSkill resolved
) {
    /** 既存の一覧生成処理向けに、基礎定義から解決結果を作れない場合の互換コンストラクタです。 */
    public SkillManagerEntry(
        @NotNull LearnedSkillInstance learnedSkill,
        @NotNull SkillDefinition definition,
        boolean permitted
    ) {
        this(
            learnedSkill,
            definition,
            permitted,
            new ResolvedLearnedSkill(learnedSkill, definition, java.util.Map.of(), java.util.Set.of())
        );
    }

    public @NotNull String bindingId() {
        return learnedSkill.getLearnedSkillId().toString();
    }
}
