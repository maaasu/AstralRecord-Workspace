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
