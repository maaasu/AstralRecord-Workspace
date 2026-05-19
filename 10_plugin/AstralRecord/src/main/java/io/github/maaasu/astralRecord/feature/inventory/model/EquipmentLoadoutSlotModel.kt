package io.github.maaasu.astralRecord.feature.inventory.model

import java.time.LocalDateTime
import java.util.UUID

data class EquipmentLoadoutSlotModel(
    val equipmentLoadoutSlotId: UUID,
    val equipmentLoadoutId: UUID,
    val slotType: String,
    val slotIndex: Int,
    val equipmentInstanceId: UUID,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val createdBy: UUID,
    val updatedBy: UUID,
    val isDeleted: Boolean,
)
