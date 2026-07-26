package io.github.maaasu.astralRecord.feature.playerclass

import io.github.maaasu.astralRecord.feature.`class`.model.ClassModel
import io.github.maaasu.astralRecord.feature.`class`.model.ClassUnlockClassLevel
import io.github.maaasu.astralRecord.feature.account.model.AccountMode
import io.github.maaasu.astralRecord.support.DesignTestFixtures
import io.github.maaasu.astralRecord.support.MockBukkitTestBase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class PlayerClassServiceTest : MockBukkitTestBase() {

    @Test
    fun displaysOnlyClassesWithPlayerProgress() {
        val player = server().addPlayer()
        val astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER)
        astPlayer.setClassProgress("mage", 4, 900L)

        val service = PlayerClassService()
        service.replaceSnapshot(
            mapOf(
                "adventurer" to classModel("adventurer", "Adventurer"),
                "mage" to classModel("mage", "Mage"),
                "warrior" to classModel("warrior", "Warrior"),
            )
        )

        assertEquals(
            listOf("adventurer", "mage"),
            service.getClassProgressViewEntries(astPlayer).map { it.id },
        )
    }

    @Test
    fun resolvesCurrentClassAndEveryAncestorAcrossMultiplePaths() {
        val service = PlayerClassService()
        service.replaceSnapshot(
            mapOf(
                "adventurer" to classModel("adventurer", "Adventurer"),
                "hunter" to classModel("hunter", "Hunter", listOf("adventurer")),
                "scout" to classModel("scout", "Scout", listOf("adventurer")),
                "ranger" to classModel("ranger", "Ranger", listOf("hunter", "scout")),
            )
        )

        assertTrue(service.isClassOrAncestor("ranger", "ranger"))
        assertTrue(service.isClassOrAncestor("ranger", "hunter"))
        assertTrue(service.isClassOrAncestor("ranger", "scout"))
        assertTrue(service.isClassOrAncestor("ranger", "adventurer"))
        assertFalse(service.isClassOrAncestor("hunter", "ranger"))
    }

    @Test
    fun ancestorResolutionStopsOnCyclicDefinitions() {
        val service = PlayerClassService()
        service.replaceSnapshot(
            mapOf(
                "cycle_a" to classModel("cycle_a", "A", listOf("cycle_b")),
                "cycle_b" to classModel("cycle_b", "B", listOf("cycle_a")),
            )
        )

        assertTrue(service.isClassOrAncestor("cycle_a", "cycle_b"))
        assertFalse(service.isClassOrAncestor("cycle_a", "adventurer"))
    }

    private fun classModel(id: String, name: String, parentIds: List<String> = emptyList()) = ClassModel(
        schemaVersion = 1,
        id = id,
        type = "CLASS",
        name = name,
        description = null,
        icon = "EXPERIENCE_BOTTLE",
        role = "DEALER",
        maxLevel = 10,
        commandOnly = false,
        unlockLevel = 1,
        unlockClassLevel = parentIds.map { ClassUnlockClassLevel(it, 1) },
        baseStats = emptyList(),
        growthPerLevel = emptyList(),
        expRate = 100,
        starterSkills = emptyList(),
        levelSkills = emptyList(),
        tags = emptyList(),
    )
}
