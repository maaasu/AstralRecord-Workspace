package io.github.maaasu.astralRecord.feature.skill.service

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService
import java.util.Locale

private const val SKILL_REFERENCE_PREFIX = "skill:"

/** クラス・現在有効なスキルツリーノード・装備中のスキルブックからスキル使用許可を解決します。 */
class SkillPermissionService(
    private val playerClassService: PlayerClassService,
    private val skillTreeService: SkillTreeService,
    private val inventoryService: InventoryService,
) {
    /**
     * 現在の装備状態を含むスキル使用許可一覧を返します。
     *
     * @param player 判定対象プレイヤー
     * @return 現在使用を許可されたスキル ID の集合
     */
    fun permittedSkillIds(player: AstPlayer): Set<String> {
        val result = linkedSetOf<String>()
        playerClassService.getLoadedClass(player.classId)?.usableSkills.orEmpty()
            .mapNotNullTo(result, ::normalize)
        skillTreeService.getUnlockedSkillIds(player)
            .mapNotNullTo(result, ::normalize)
        inventoryService.getEquippedSkillbookUsableSkills(player)
            .mapNotNullTo(result, ::normalize)
        return result
    }

    /**
     * 指定スキルに現在の使用許可があるか判定します。
     *
     * @param player 判定対象プレイヤー
     * @param skillId 判定するスキル ID または参照
     * @return 使用許可がある場合 true
     */
    fun isPermitted(player: AstPlayer, skillId: String): Boolean {
        val normalized = normalize(skillId) ?: return false
        return permittedSkillIds(player).contains(normalized)
    }

    private fun normalize(raw: String?): String? = raw
        ?.trim()
        ?.let { if (it.startsWith(SKILL_REFERENCE_PREFIX, ignoreCase = true)) it.substring(SKILL_REFERENCE_PREFIX.length) else it }
        ?.takeIf { it.isNotBlank() }
        ?.lowercase(Locale.ROOT)
}
