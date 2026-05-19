package io.github.maaasu.astralRecord.feature.inventory.model

import java.time.LocalDateTime
import java.util.UUID

data class InventoryModel(
    val inventoryId: UUID,
    val accountId: UUID,
    val inventoryType: InventoryType,
    val inventoryProfile: String,
    val slotCapacity: Int?,
    val isEnabled: Boolean,
    val metadataJson: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val createdBy: UUID,
    val updatedBy: UUID,
    val isDeleted: Boolean,
)
