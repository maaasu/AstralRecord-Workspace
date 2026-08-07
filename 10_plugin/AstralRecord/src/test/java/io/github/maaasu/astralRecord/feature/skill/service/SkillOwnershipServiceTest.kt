package io.github.maaasu.astralRecord.feature.skill.service

import io.github.maaasu.astralRecord.feature.account.model.AccountMode
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance
import io.github.maaasu.astralRecord.feature.skill.repository.LearnedSkillRepository
import io.github.maaasu.astralRecord.support.DesignTestFixtures
import io.github.maaasu.astralRecord.support.MockBukkitTestBase
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.util.UUID

class SkillOwnershipServiceTest : MockBukkitTestBase() {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 所持判定はクラス許可ではなくAPIからロードした習得済み個体だけを正本にする。
     */
    @Test
    fun resolvesOwnedSkillsOnlyFromLearnedInstances() {
        val astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER)
        val learnedService = learnedSkillService()
        learnedService.applyInitialSkills(
            astPlayer.account.uuid,
            listOf(
                learned(astPlayer.account.uuid, "adventurer_smash"),
                learned(astPlayer.account.uuid, "mob_goblin_slash"),
            ),
        )
        val service = SkillOwnershipService(learnedService)

        assertEquals(setOf("adventurer_smash", "mob_goblin_slash"), service.ownedSkillIds(astPlayer))
        assertTrue(service.owns(astPlayer, "adventurer_smash"))
        assertFalse(service.owns(astPlayer, "class_permission_only"))
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 同一skillIdの複数個体を別UUIDとして保持し、クラス変更でも所持状態を失わない。
     */
    @Test
    fun keepsDuplicateInstancesAcrossClassChanges() {
        val astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER)
        val learnedService = learnedSkillService()
        val first = learned(astPlayer.account.uuid, "adventurer_smash")
        val second = learned(astPlayer.account.uuid, "adventurer_smash")
        learnedService.applyInitialSkills(astPlayer.account.uuid, listOf(first, second))
        val service = SkillOwnershipService(learnedService)

        astPlayer.selectClass("mage")
        assertTrue(service.ownsInstance(astPlayer, first.learnedSkillId))
        assertTrue(service.ownsInstance(astPlayer, second.learnedSkillId))

        astPlayer.selectClass("swordsman")

        assertEquals(2, service.learnedSkills(astPlayer).size)
        assertTrue(service.owns(astPlayer, "adventurer_smash"))
    }

    private fun learnedSkillService() = LearnedSkillService(
        mock(Plugin::class.java),
        mock(LearnedSkillRepository::class.java),
        mock(InventoryService::class.java),
    )

    private fun learned(accountId: UUID, skillId: String) = LearnedSkillInstance(
        learnedSkillId = UUID.randomUUID(),
        accountId = accountId,
        skillId = skillId,
        level = 1,
    )
}
