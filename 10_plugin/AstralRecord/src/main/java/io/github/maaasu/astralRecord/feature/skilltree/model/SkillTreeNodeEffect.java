package io.github.maaasu.astralRecord.feature.skilltree.model;

/** スキルツリーノードが有効な間に適用する型付き効果です。 */
public sealed interface SkillTreeNodeEffect permits SkillTreeSkillEffect, SkillTreeStatusEffect {
}
