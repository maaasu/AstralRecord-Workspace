package io.github.maaasu.astralRecord.feature.playerclass

import io.github.maaasu.astralRecord.feature.`class`.model.ClassModel
import io.github.maaasu.astralRecord.feature.`class`.model.ClassUnlockClassLevel
import io.github.maaasu.astralRecord.feature.account.model.AccountMode
import io.github.maaasu.astralRecord.support.DesignTestFixtures
import io.github.maaasu.astralRecord.support.MockBukkitTestBase
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows

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

    @Test
    fun returnsConfiguredThreeCharacterShortName() {
        val service = PlayerClassService()
        service.replaceSnapshot(mapOf("mage" to classModel("mage", "&bメイジ", shortName = "&b魔術師")))

        assertEquals("§b魔術師", service.getShortDisplayName("mage"))
    }

    @Test
    fun updatesTabListNameWithFullClassName() {
        val player = server().addPlayer()
        val astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER)
        astPlayer.selectClass("mage")
        val service = PlayerClassService()
        service.replaceSnapshot(mapOf("mage" to classModel("mage", "&bメイジ", shortName = "&b魔術師")))

        service.updatePlayerListName(astPlayer)

        assertEquals(
            "[メイジ] ${player.name}",
            PlainTextComponentSerializer.plainText().serialize(player.playerListName()),
        )
    }

    @Test
    fun rejectsDuplicateVisibleShortNames() {
        val service = PlayerClassService()

        assertThrows(IllegalArgumentException::class.java) {
            service.replaceSnapshot(
                mapOf(
                    "mage" to classModel("mage", "Mage", shortName = "&b魔術師"),
                    "wizard" to classModel("wizard", "Wizard", shortName = "&d魔術師"),
                ),
            )
        }
    }

    private fun classModel(
        id: String,
        name: String,
        parentIds: List<String> = emptyList(),
        shortName: String = id.takeLast(3).padStart(3, '_'),
    ) = ClassModel(
        schemaVersion = 1,
        id = id,
        type = "CLASS",
        name = name,
        shortName = shortName,
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
