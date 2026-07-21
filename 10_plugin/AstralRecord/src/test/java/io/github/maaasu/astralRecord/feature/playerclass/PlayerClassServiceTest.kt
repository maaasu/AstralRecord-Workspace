package io.github.maaasu.astralRecord.feature.playerclass

import io.github.maaasu.astralRecord.feature.`class`.model.ClassModel
import io.github.maaasu.astralRecord.feature.account.model.AccountMode
import io.github.maaasu.astralRecord.support.DesignTestFixtures
import io.github.maaasu.astralRecord.support.MockBukkitTestBase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

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

    private fun classModel(id: String, name: String) = ClassModel(
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
        unlockClassLevel = emptyList(),
        baseStats = emptyList(),
        growthPerLevel = emptyList(),
        expRate = 100,
        starterSkills = emptyList(),
        levelSkills = emptyList(),
        tags = emptyList(),
    )
}
