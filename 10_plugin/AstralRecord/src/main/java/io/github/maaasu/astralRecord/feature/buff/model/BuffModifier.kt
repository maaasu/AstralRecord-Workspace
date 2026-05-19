package io.github.maaasu.astralRecord.feature.buff.model

import io.github.maaasu.astralRecord.feature.status.model.StatusType

/**
 * バフの単一補正を表します。
 */
data class BuffModifier(
    val status: StatusType,
    val type: BuffModifierType,
    val value: Double,
)
