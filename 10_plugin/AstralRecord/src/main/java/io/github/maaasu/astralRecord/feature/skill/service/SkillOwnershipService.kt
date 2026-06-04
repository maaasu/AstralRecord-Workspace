package io.github.maaasu.astralRecord.feature.skill.service

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment
import io.github.maaasu.astralRecord.feature.item.service.ItemService
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService

/**
 * プレイヤーが現在所持している扱いになるスキル ID を解決します。
 */
class SkillOwnershipService(
    private val playerClassService: PlayerClassService,
    private val inventoryService: InventoryService,
    private val itemService: ItemService,
    private val skillTreeService: SkillTreeService,
) {
    /**
     * 職業と現在の装備ロードアウトから所持スキル ID を収集します。
     */
    fun ownedSkillIds(player: AstPlayer): Set<String> {
        val skillIds = linkedSetOf<String>()
        addClassSkills(player, skillIds)
        addEquipmentSkills(player, skillIds)
        addSkillTreeSkills(player, skillIds)
        return skillIds
    }

    /**
     * 指定スキルを所持しているかを判定します。
     */
    fun owns(player: AstPlayer, skillId: String): Boolean =
        ownedSkillIds(player).contains(skillId)

    private fun addClassSkills(player: AstPlayer, skillIds: MutableSet<String>) {
        val classModel = playerClassService.getLoadedClass(player.classId) ?: return
        skillIds.addAllNotBlank(classModel.starterSkills)
        for (levelSkill in classModel.levelSkills) {
            if (levelSkill.level <= player.classLevel) {
                skillIds.addNotBlank(levelSkill.skill)
            }
        }
    }

    private fun addEquipmentSkills(player: AstPlayer, skillIds: MutableSet<String>) {
        val loadout = inventoryService.getActiveEquipmentLoadout(player.account.uuid) ?: return
        val setCounts = linkedMapOf<String, Int>()
        for (slot in loadout.slots) {
            if (slot.isDeleted) continue
            val instance = itemService.findEquipmentInstanceById(slot.equipmentInstanceId.toString()) ?: continue
            val item = itemService.findLoadedById(instance.itemId) ?: itemService.loadItem(instance.itemId) ?: continue
            val equipment = item.equipment ?: continue
            addEquipmentDefinitionSkills(skillIds, equipment)
            addRuneSkills(skillIds, instance)
            equipment.setId?.takeIf { it.isNotBlank() }?.trim()?.let { setId ->
                setCounts[setId] = (setCounts[setId] ?: 0) + 1
            }
        }
        addSetEffectSkills(skillIds, setCounts)
    }

    private fun addEquipmentDefinitionSkills(skillIds: MutableSet<String>, equipment: ItemEquipment) {
        skillIds.addAllNotBlank(equipment.skills)
    }

    private fun addRuneSkills(skillIds: MutableSet<String>, instance: EquipmentInstance) {
        for (runeEntry in instance.runes) {
            val runeInstance = itemService.findRuneInstanceById(runeEntry.runeId) ?: continue
            val runeItem = itemService.findLoadedById(runeInstance.itemId) ?: itemService.loadItem(runeInstance.itemId) ?: continue
            skillIds.addAllNotBlank(runeItem.rune?.skills.orEmpty())
        }
    }

    private fun addSetEffectSkills(skillIds: MutableSet<String>, setCounts: Map<String, Int>) {
        for ((setId, equippedCount) in setCounts) {
            val setEffect = itemService.findSetEffectById(setId) ?: continue
            for (piece in setEffect.pieces) {
                if (piece.count <= 0 || equippedCount < piece.count) {
                    continue
                }
                skillIds.addAllNotBlank(piece.skills)
            }
        }
    }

    private fun addSkillTreeSkills(player: AstPlayer, skillIds: MutableSet<String>) {
        skillIds.addAll(skillTreeService.getUnlockedSkillIds(player))
    }

    private fun MutableSet<String>.addAllNotBlank(values: Iterable<String>) {
        values.forEach { addSkillId(this, it) }
    }

    private fun MutableSet<String>.addNotBlank(value: String?) {
        addSkillId(this, value)
    }

    private fun addSkillId(skillIds: MutableSet<String>, value: String?) {
        if (!value.isNullOrBlank()) skillIds.add(value.trim())
    }
}
