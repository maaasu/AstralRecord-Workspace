package io.github.maaasu.astralRecord.feature.skilltree.model;

import org.jetbrains.annotations.NotNull;

/**
 * ノードの条件成立中にスキル使用許可を付与する効果です。
 *
 * @param skillId 使用を許可するスキル ID
 */
public record SkillTreeSkillEffect(@NotNull String skillId) implements SkillTreeNodeEffect {
}
