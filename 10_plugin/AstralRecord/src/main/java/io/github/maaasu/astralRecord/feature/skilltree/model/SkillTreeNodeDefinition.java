package io.github.maaasu.astralRecord.feature.skilltree.model;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * filebase で定義されるスキルツリーノードのマスタデータです。
 */
public record SkillTreeNodeDefinition(
        @NotNull String id,
        @NotNull String positionId,
        @NotNull String name,
        @NotNull Material icon,
        @NotNull List<String> lore,
        @NotNull List<String> tags,
        @NotNull List<String> skillIds,
        @NotNull List<SkillTreeNodeStatusDefinition> statuses
) {
}
