package io.github.maaasu.astralRecord.feature.inventory.model

import java.time.LocalDateTime
import java.util.UUID

data class InventoryEntryModel(
    val inventoryEntryId: UUID,
    val inventoryId: UUID,
    val slotIndex: Int?,
    val itemCategory: String,
    val itemId: String?,
    val instanceType: String?,
    val instanceId: UUID?,
    val quantity: Long,
    val metadataJson: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val createdBy: UUID,
    val updatedBy: UUID,
    val isDeleted: Boolean,
)
