package io.github.maaasu.astralRecord.feature.skilltree.model;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * filebase で定義されるスキルツリーノードのマスタデータです。
 */
public record SkillTreeNodeDefinition(
        @NotNull String nodeId,
        @NotNull String name,
        @NotNull Material icon,
        @NotNull List<String> lore,
        @NotNull List<String> tags,
        @NotNull SkillTreePointType pointType,
        int pointCost,
        @NotNull SkillTreeUnlockCondition unlockCondition,
        @NotNull List<SkillTreeNodeEffect> effects
) {
    public SkillTreeNodeDefinition {
        pointCost = Math.max(0, pointCost);
        lore = List.copyOf(lore);
        tags = List.copyOf(tags);
        unlockCondition = unlockCondition == null ? SkillTreeUnlockCondition.NONE : unlockCondition;
        effects = List.copyOf(effects);
    }

    public SkillTreeNodeDefinition(
            @NotNull String nodeId,
            @NotNull String name,
            @NotNull Material icon,
            @NotNull List<String> lore,
            @NotNull List<String> tags,
            @NotNull SkillTreePointType pointType,
            int pointCost,
            @NotNull List<SkillTreeNodeEffect> effects
    ) {
        this(nodeId, name, icon, lore, tags, pointType, pointCost, SkillTreeUnlockCondition.NONE, effects);
    }

    /**
     * スキル付与効果だけを返します。
     *
     * @return 定義順を維持したスキル付与効果
     */
    public @NotNull List<SkillTreeSkillEffect> skillEffects() {
        return effects.stream()
                .filter(SkillTreeSkillEffect.class::isInstance)
                .map(SkillTreeSkillEffect.class::cast)
                .toList();
    }

    /**
     * ステータス補正効果だけを返します。
     *
     * @return 定義順を維持したステータス補正効果
     */
    public @NotNull List<SkillTreeStatusEffect> statusEffects() {
        return effects.stream()
                .filter(SkillTreeStatusEffect.class::isInstance)
                .map(SkillTreeStatusEffect.class::cast)
                .toList();
    }
}
