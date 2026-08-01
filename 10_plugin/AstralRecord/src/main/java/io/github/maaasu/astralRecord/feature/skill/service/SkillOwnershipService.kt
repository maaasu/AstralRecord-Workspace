package io.github.maaasu.astralRecord.feature.skill.service

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance
import java.util.UUID

/**
 * 習得済みスキル個体だけを「所持」として解決します。
 * クラス・スキルツリーによる使用許可は [SkillPermissionService] が別に判定します。
 */
class SkillOwnershipService(
    private val learnedSkillService: LearnedSkillService,
) {
    fun learnedSkills(player: AstPlayer): List<LearnedSkillInstance> =
        learnedSkillService.getLearnedSkills(player.account.uuid)

    fun ownedSkillIds(player: AstPlayer): Set<String> =
        learnedSkills(player).mapTo(linkedSetOf()) { it.skillId }

    fun owns(player: AstPlayer, skillId: String): Boolean =
        learnedSkillService.ownsSkill(player.account.uuid, skillId)

    fun ownsInstance(player: AstPlayer, learnedSkillId: UUID): Boolean =
        learnedSkillService.findInstance(player.account.uuid, learnedSkillId) != null

    fun findInstance(player: AstPlayer, learnedSkillId: String?): LearnedSkillInstance? =
        learnedSkillService.findInstance(player.account.uuid, learnedSkillId)
}
