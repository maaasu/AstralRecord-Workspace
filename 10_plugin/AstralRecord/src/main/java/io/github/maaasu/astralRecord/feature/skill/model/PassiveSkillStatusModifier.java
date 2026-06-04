package io.github.maaasu.astralRecord.feature.skill.model;

import io.github.maaasu.astralRecord.feature.status.model.StatusModifierType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.jetbrains.annotations.NotNull;

/**
 * パッシブスキルが付与するステータス補正です。
 *
 * @param statusType 対象ステータス
 * @param type 補正種別
 * @param value 補正値
 */
public record PassiveSkillStatusModifier(
    @NotNull StatusType statusType,
    @NotNull StatusModifierType type,
    double value
) {
}
