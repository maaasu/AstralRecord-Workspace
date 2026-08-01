package io.github.maaasu.astralRecord.feature.inventory.model

import java.time.LocalDateTime
import java.util.UUID

data class InventoryEntryDraft(
    val slotIndex: Int?,
    val itemCategory: String,
    val itemId: String?,
    val instanceType: String?,
    val instanceId: UUID?,
    val quantity: Long = 1,
    val metadataJson: String? = null,
    val inventoryEntryId: UUID? = null,
    val expectedUpdatedAt: LocalDateTime? = null,
)
