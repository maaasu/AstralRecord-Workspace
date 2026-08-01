package io.github.maaasu.astralRecord.feature.skill.service

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService

private const val SKILL_REFERENCE_PREFIX = "skill:"

/** クラスまたは現在有効なスキルツリーノードからスキル使用許可を解決します。 */
class SkillPermissionService(
    private val playerClassService: PlayerClassService,
    private val skillTreeService: SkillTreeService,
) {
    fun permittedSkillIds(player: AstPlayer): Set<String> {
        val result = linkedSetOf<String>()
        playerClassService.getLoadedClass(player.classId)?.usableSkills.orEmpty()
            .mapNotNullTo(result, ::normalize)
        skillTreeService.getUnlockedSkillIds(player)
            .mapNotNullTo(result, ::normalize)
        return result
    }

    fun isPermitted(player: AstPlayer, skillId: String): Boolean {
        val normalized = normalize(skillId) ?: return false
        return permittedSkillIds(player).contains(normalized)
    }

    private fun normalize(raw: String?): String? = raw
        ?.trim()
        ?.removePrefix(SKILL_REFERENCE_PREFIX)
        ?.takeIf { it.isNotBlank() }
}
