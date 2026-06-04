package io.github.maaasu.astralRecord.feature.skilltree.model;

import io.github.maaasu.astralRecord.feature.status.model.StatusModifierType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.jetbrains.annotations.NotNull;

/**
 * スキルツリーノードが付与する直接ステータス補正です。
 *
 * @param statusType 対象ステータス
 * @param type 補正種別
 * @param value 補正値
 */
public record SkillTreeNodeStatusDefinition(
    @NotNull StatusType statusType,
    @NotNull StatusModifierType type,
    double value
) {
}
