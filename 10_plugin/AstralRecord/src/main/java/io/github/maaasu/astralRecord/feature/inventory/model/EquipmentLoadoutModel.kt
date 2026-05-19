package io.github.maaasu.astralRecord.feature.inventory.model

import java.time.LocalDateTime
import java.util.UUID

data class EquipmentLoadoutModel(
    val equipmentLoadoutId: UUID,
    val accountId: UUID,
    val loadoutProfile: String,
    val loadoutName: String,
    val sortOrder: Int,
    val isActive: Boolean,
    val metadataJson: String?,
    val slots: List<EquipmentLoadoutSlotModel>,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val createdBy: UUID,
    val updatedBy: UUID,
    val isDeleted: Boolean,
)
