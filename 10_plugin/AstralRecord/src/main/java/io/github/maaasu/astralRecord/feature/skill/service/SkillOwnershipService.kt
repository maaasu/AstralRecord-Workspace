package io.github.maaasu.astralRecord.feature.skill.service

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment
import io.github.maaasu.astralRecord.feature.item.service.ItemService
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService

/**
 * プレイヤーが現在所有している扱いにするスキル ID を解決します。
 */
class SkillOwnershipService(
    private val playerClassService: PlayerClassService,
    private val inventoryService: InventoryService,
    private val itemService: ItemService,
) {
    /**
     * 職業と現在の装備ロードアウトから所有スキル ID を収集します。
     */
    fun ownedSkillIds(player: AstPlayer): Set<String> {
        val skillIds = linkedSetOf<String>()
        addClassSkills(player, skillIds)
        addEquipmentSkills(player, skillIds)
        return skillIds
    }

    /**
     * 指定スキルを現在所有しているか判定します。
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
        for (slot in loadout.slots) {
            if (slot.isDeleted) continue
            val instance = itemService.findEquipmentInstanceById(slot.equipmentInstanceId.toString()) ?: continue
            val item = itemService.findLoadedById(instance.itemId) ?: itemService.loadItem(instance.itemId) ?: continue
            val equipment = item.equipment ?: continue
            addEquipmentDefinitionSkills(skillIds, equipment)
        }
    }

    private fun addEquipmentDefinitionSkills(skillIds: MutableSet<String>, equipment: ItemEquipment) {
        skillIds.addAllNotBlank(equipment.skills)
        addSkillId(skillIds, equipment.onUse?.leftClickSkillId)
        addSkillId(skillIds, equipment.onUse?.rightClickSkillId)
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
