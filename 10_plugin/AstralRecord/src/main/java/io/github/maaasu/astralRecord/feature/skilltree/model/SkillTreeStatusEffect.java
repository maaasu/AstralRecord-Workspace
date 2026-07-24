package io.github.maaasu.astralRecord.feature.skilltree.model;

import io.github.maaasu.astralRecord.feature.status.model.StatusModifierType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.jetbrains.annotations.NotNull;

/**
 * ノードから直接ステータス補正を付与する効果です。
 *
 * @param statusType 対象ステータス
 * @param modifierType 補正種別
 * @param value 補正値
 */
public record SkillTreeStatusEffect(
        @NotNull StatusType statusType,
        @NotNull StatusModifierType modifierType,
        double value
) implements SkillTreeNodeEffect {
}
