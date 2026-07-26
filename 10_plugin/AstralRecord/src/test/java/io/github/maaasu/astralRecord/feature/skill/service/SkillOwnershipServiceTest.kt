package io.github.maaasu.astralRecord.feature.skill.service

import io.github.maaasu.astralRecord.feature.`class`.model.ClassLevelSkill
import io.github.maaasu.astralRecord.feature.`class`.model.ClassModel
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService
import io.github.maaasu.astralRecord.feature.item.service.BuiltInWeaponAttackDefinitions
import io.github.maaasu.astralRecord.feature.item.service.ItemService
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService
import io.github.maaasu.astralRecord.feature.account.model.AccountMode
import io.github.maaasu.astralRecord.support.DesignTestFixtures
import io.github.maaasu.astralRecord.support.MockBukkitTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class SkillOwnershipServiceTest : MockBukkitTestBase() {

    @Test
    fun resolvesAdministratorLevelOneSkillReferencesToCanonicalIds() {
        val playerClassService = PlayerClassService()
        playerClassService.replaceSnapshot(
            mapOf(
                "administrator" to classModel(
                    id = "administrator",
                    starterSkills = listOf(" skill:starter_skill ", " raw_starter_skill ", "skill:", " "),
                    levelSkills = listOf(
                        ClassLevelSkill(1, "skill:level_one_skill"),
                        ClassLevelSkill(1, "raw_level_one_skill"),
                        ClassLevelSkill(2, "skill:level_two_skill"),
                    ),
                ),
            ),
        )
        val astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER)
        astPlayer.selectClass("administrator")

        val ownedSkillIds = ownershipService(playerClassService).ownedSkillIds(astPlayer)

        assertEquals(
            setOf("starter_skill", "raw_starter_skill", "level_one_skill", "raw_level_one_skill"),
            ownedSkillIds,
        )
        assertFalse(ownedSkillIds.any { it.startsWith("skill:") })
        assertFalse(ownedSkillIds.contains("mob_goblin_slash"))
        assertFalse(ownedSkillIds.contains(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MELEE))
        assertFalse(ownedSkillIds.contains(BuiltInWeaponAttackDefinitions.SPECIAL_ATTACK_MELEE))
    }

    @Test
    fun switchingToNormalClassDoesNotGrantAdministratorSkills() {
        val administratorSkillId = "administrator_only_skill"
        val playerClassService = PlayerClassService()
        playerClassService.replaceSnapshot(
            mapOf(
                "administrator" to classModel(
                    id = "administrator",
                    levelSkills = listOf(ClassLevelSkill(1, "skill:$administratorSkillId")),
                ),
                "swordsman" to classModel(id = "swordsman"),
            ),
        )
        val astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER)
        astPlayer.selectClass("administrator")
        val ownershipService = ownershipService(playerClassService)

        assertTrue(ownershipService.owns(astPlayer, administratorSkillId))

        astPlayer.selectClass("swordsman")

        assertEquals(emptySet<String>(), ownershipService.ownedSkillIds(astPlayer))
        assertFalse(ownershipService.owns(astPlayer, administratorSkillId))
    }

    private fun ownershipService(playerClassService: PlayerClassService) = SkillOwnershipService(
        playerClassService = playerClassService,
        inventoryService = mock(InventoryService::class.java),
        itemService = mock(ItemService::class.java),
        skillTreeService = mock(SkillTreeService::class.java),
    )

    private fun classModel(
        id: String,
        starterSkills: List<String> = emptyList(),
        levelSkills: List<ClassLevelSkill> = emptyList(),
    ) = ClassModel(
        schemaVersion = 1,
        id = id,
        type = "CLASS",
        name = id,
        description = null,
        icon = "EXPERIENCE_BOTTLE",
        role = "DEALER",
        maxLevel = 10,
        commandOnly = id == "administrator",
        unlockLevel = 1,
        unlockClassLevel = emptyList(),
        baseStats = emptyList(),
        growthPerLevel = emptyList(),
        expRate = 100,
        starterSkills = starterSkills,
        levelSkills = levelSkills,
        tags = emptyList(),
    )
}
