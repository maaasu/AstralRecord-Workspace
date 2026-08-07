package io.github.maaasu.astralRecord.feature.skill.service

import io.github.maaasu.astralRecord.feature.`class`.model.ClassModel
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SkillPermissionServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 使用許可
     * 検証契約: 現在クラスのusableSkillsまたは現在有効なスキルツリーノードのどちらかにあれば使用を許可する。
     */
    @Test
    fun permitsUnionOfCurrentClassAndEffectiveSkillTreeNodes() {
        val player = mock(AstPlayer::class.java)
        val playerClassService = mock(PlayerClassService::class.java)
        val skillTreeService = mock(SkillTreeService::class.java)
        val classModel = mock(ClassModel::class.java)
        `when`(player.classId).thenReturn("adventurer")
        `when`(playerClassService.getLoadedClass("adventurer")).thenReturn(classModel)
        `when`(classModel.usableSkills).thenReturn(listOf("skill:adventurer_astral_edge", "adventurer_smash"))
        `when`(skillTreeService.getUnlockedSkillIds(player)).thenReturn(
            setOf("skill:adventurer_smash", "adventurer_astral_edge"),
        )
        val service = SkillPermissionService(playerClassService, skillTreeService)

        assertEquals(
            setOf("adventurer_astral_edge", "adventurer_smash"),
            service.permittedSkillIds(player),
        )
        assertTrue(service.isPermitted(player, "skill:adventurer_astral_edge"))
        assertTrue(service.isPermitted(player, "adventurer_smash"))
        assertFalse(service.isPermitted(player, "mob_goblin_slash"))
    }
}
