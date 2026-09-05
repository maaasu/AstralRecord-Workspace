package io.github.maaasu.astralRecord.feature.skill.model

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryOperationSnapshot
import java.time.LocalDateTime
import java.util.UUID

data class LearnedSkillInstance(
    val learnedSkillId: UUID,
    val accountId: UUID,
    val skillId: String,
    val level: Int,
    val sigils: List<LearnedSkillSigil> = emptyList(),
    val version: Int = 0,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null,
) {
    fun hasSigil(sigilId: String): Boolean = sigils.any { it.sigilId == sigilId }
}

data class LearnedSkillSigil(
    val learnedSkillSigilId: UUID,
    val sigilId: String,
    val equipGroupId: String,
    val slotIndex: Int,
)

data class LearnedSkillSigilDetachResult(
    val skill: LearnedSkillInstance,
    val returnedInventoryEntryId: UUID,
    val inventorySnapshot: InventoryOperationSnapshot? = null,
)

data class LearnedSkillConsumedMaterial(
    val inventoryEntryId: UUID,
    val consumedAmount: Long,
)

data class LearnedSkillMaterialMutationResult(
    val skill: LearnedSkillInstance,
    val consumedMaterials: List<LearnedSkillConsumedMaterial> = emptyList(),
    val inventorySnapshot: InventoryOperationSnapshot? = null,
)

/** シジル装着後の習得個体と、消費entryの任意の正本snapshotです。 */
data class LearnedSkillInventoryMutationResult(
    val skill: LearnedSkillInstance,
    val inventorySnapshot: InventoryOperationSnapshot? = null,
)

enum class LearnedSkillMutationFailure {
    ACCOUNT_NOT_FOUND,
    LEARNED_SKILL_NOT_FOUND,
    SKILL_NOT_FOUND,
    SIGIL_NOT_FOUND,
    SIGIL_ATTACHMENT_NOT_FOUND,
    INVENTORY_NOT_FOUND,
    INVALID_MATERIAL,
    MAX_LEVEL_REACHED,
    NO_SIGIL_SLOT,
    SIGIL_NOT_ALLOWED,
    DUPLICATE_SIGIL_GROUP,
    IDEMPOTENCY_CONFLICT,
    UNKNOWN,
}

class LearnedSkillMutationException @JvmOverloads constructor(
    val failure: LearnedSkillMutationFailure,
    message: String,
    /** HTTP response status when the mutation reached the API, otherwise null. */
    val statusCode: Int? = null,
) : RuntimeException(message)
