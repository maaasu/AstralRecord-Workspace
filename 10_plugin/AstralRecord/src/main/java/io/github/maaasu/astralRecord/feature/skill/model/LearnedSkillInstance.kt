package io.github.maaasu.astralRecord.feature.skill.model

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
    UNKNOWN,
}

class LearnedSkillMutationException(
    val failure: LearnedSkillMutationFailure,
    message: String,
) : RuntimeException(message)
