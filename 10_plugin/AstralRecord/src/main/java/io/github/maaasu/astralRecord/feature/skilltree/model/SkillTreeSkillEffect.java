package io.github.maaasu.astralRecord.feature.skilltree.model;

import org.jetbrains.annotations.NotNull;

/**
 * ノードからスキル所有権を付与する効果です。
 *
 * @param skillId 付与するスキル ID
 */
public record SkillTreeSkillEffect(@NotNull String skillId) implements SkillTreeNodeEffect {
}
