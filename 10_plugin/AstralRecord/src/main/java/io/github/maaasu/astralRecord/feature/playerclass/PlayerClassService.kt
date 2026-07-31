package io.github.maaasu.astralRecord.feature.playerclass

import io.github.maaasu.astralRecord.AstralRecord
import io.github.maaasu.astralRecord.feature.account.service.AccountService
import io.github.maaasu.astralRecord.feature.`class`.model.ClassModel
import io.github.maaasu.astralRecord.feature.`class`.model.ClassStat
import io.github.maaasu.astralRecord.feature.`class`.service.ClassService
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassExperienceResult
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassLevelSetResult
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassProgressViewEntry
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassViewEntry
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService
import io.github.maaasu.astralRecord.feature.status.model.StatusType
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.math.roundToLong

class PlayerClassService @JvmOverloads constructor(
    private val accountService: AccountService? = null,
) {
    private val classService = ClassService()
    private var skillTreeService: SkillTreeService? = null

    fun setSkillTreeService(service: SkillTreeService) {
        skillTreeService = service
    }

    fun loadAll(): Int = classService.loadAll()

    fun loadSnapshot(): Map<String, ClassModel> = classService.loadSnapshot()

    fun replaceSnapshot(snapshot: Map<String, ClassModel>) {
        classService.replaceSnapshot(snapshot)
        AstPlayerCache.getAll().forEach(::updatePlayerListName)
    }

    fun getLoadedClass(classId: String): ClassModel? = classService.getLoadedClass(classId)

    fun getDisplayName(classId: String): String {
        val model = classService.getLoadedClass(classId) ?: return classId
        return ColorCodeUtil.toLegacyText(model.name, classId)
    }

    /**
     * チャット表示用の3文字クラス短縮名を返します。
     *
     * @param classId クラス ID
     * @return 色コードを反映した短縮名。未ロードの場合はクラス ID
     */
    fun getShortDisplayName(classId: String): String {
        val model = classService.getLoadedClass(classId) ?: return classId
        return ColorCodeUtil.toLegacyText(model.shortName, classId)
    }

    /**
     * タブのプレイヤーリスト名を、正式クラス名タグ付きで更新します。
     *
     * @param astPlayer 更新対象プレイヤー
     */
    fun updatePlayerListName(astPlayer: AstPlayer) {
        astPlayer.bukkit.playerListName(
            PlayerMsgResource.formatComponent(
                PlayerMsgId.P_5948.id,
                getDisplayName(astPlayer.classId),
                astPlayer.bukkit.name,
            ),
        )
    }

    fun getLoadedClasses(): List<ClassModel> = classService.getLoadedClasses()

    fun getStatusBonus(astPlayer: AstPlayer, statusType: StatusType): Double {
        val model = classService.getLoadedClass(astPlayer.classId) ?: return 0.0
        val base = classStatValue(model.baseStats, statusType)
        val level = astPlayer.classLevel.coerceIn(1, maxClassLevel(model))
        val growth = classStatValue(model.growthPerLevel, statusType) * (level - 1)
        return base + growth
    }

    /**
     * クラス経験値を加算し、到達済みレベルをセッション状態へ反映します。
     *
     * @param astPlayer 対象プレイヤー
     * @param experience 加算する基礎経験値
     * @return クラス経験値加算結果
     */
    fun grantClassExperience(astPlayer: AstPlayer, experience: Int): ClassExperienceResult {
        val model = classService.getLoadedClass(astPlayer.classId)
            ?: return ClassExperienceResult(astPlayer.classLevel.coerceAtLeast(1), astPlayer.classLevel.coerceAtLeast(1), 0, 0)
        val maxLevel = maxClassLevel(model)
        val previousLevel = astPlayer.classLevel.coerceIn(1, maxLevel)
        if (experience <= 0) {
            return ClassExperienceResult(previousLevel, previousLevel, 0, 0)
        }

        val totalExperience = (astPlayer.classExperience + experience).coerceAtLeast(0L)
        var level = previousLevel
        while (level < maxLevel && totalExperience >= totalRequiredClassExperienceForLevel(model, level + 1)) {
            level++
        }
        astPlayer.classExperience = totalExperience
        astPlayer.classLevel = level
        persistClassProgress(astPlayer)
        return ClassExperienceResult(previousLevel, level, experience, (level - previousLevel).coerceAtLeast(0))
    }

    /**
     * プレイヤーの現在クラスを変更し、クラス進行度の保存を予約します。
     *
     * @param astPlayer 変更対象プレイヤー
     * @param classId 変更後のクラス ID
     */
    fun changeClass(astPlayer: AstPlayer, classId: String) {
        astPlayer.selectClass(classId)
        persistClassProgress(astPlayer)
        skillTreeService?.refreshProgressDerivedState(astPlayer)
        updatePlayerListName(astPlayer)
    }

    /**
     * 指定クラスのレベルを上限・下限内に丸めて設定します。
     *
     * @param astPlayer 対象プレイヤー
     * @param classId 設定対象クラス ID
     * @param requestedLevel 設定要求レベル
     * @return 設定前後のレベルとクラス上限
     */
    fun setClassLevel(astPlayer: AstPlayer, classId: String, requestedLevel: Long): ClassLevelSetResult? {
        val model = classService.getLoadedClass(classId) ?: return null
        val maxLevel = maxClassLevel(model)
        val previous = astPlayer.getClassProgress(model.id)
        val previousLevel = previous.level.coerceIn(1, maxLevel)
        val currentLevel = requestedLevel.coerceIn(1L, maxLevel.toLong()).toInt()
        val experience = totalRequiredClassExperienceForLevel(model, currentLevel)
        astPlayer.setClassProgress(model.id, currentLevel, experience)
        persistClassProgress(astPlayer, model.id, currentLevel, experience)
        skillTreeService?.refreshProgressDerivedState(astPlayer)
        return ClassLevelSetResult(model.id, previousLevel, currentLevel, maxLevel)
    }

    /**
     * 必要クラスが現在クラスそのもの、またはいずれかの転職前提クラスなら true を返します。
     * 複数の前提経路をすべて辿り、循環定義があっても停止します。
     */
    fun matchesCurrentClassCondition(astPlayer: AstPlayer, requiredClassId: String): Boolean =
        isClassOrAncestor(astPlayer.classId, requiredClassId)

    fun isClassOrAncestor(currentClassId: String, requiredClassId: String): Boolean {
        val required = requiredClassId.trim().lowercase(Locale.ROOT)
        if (required.isEmpty()) {
            return true
        }
        val queue = java.util.ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        queue.add(currentClassId.trim().lowercase(Locale.ROOT))
        while (queue.isNotEmpty()) {
            val classId = queue.removeFirst()
            if (!visited.add(classId)) {
                continue
            }
            if (classId == required) {
                return true
            }
            val model = classService.getLoadedClass(classId) ?: continue
            model.unlockClassLevel.forEach { requirement ->
                queue.addLast(requirement.classId.trim().lowercase(Locale.ROOT))
            }
        }
        return false
    }

    /**
     * 現在クラスレベル内の経験値進捗率を返します。
     *
     * @param astPlayer 対象プレイヤー
     * @return 0.0 から 1.0 の進捗率
     */
    fun classExperienceProgress(astPlayer: AstPlayer): Double {
        val model = classService.getLoadedClass(astPlayer.classId) ?: return 0.0
        return classExperienceProgress(model, astPlayer.classLevel, astPlayer.classExperience)
    }

    private fun classExperienceProgress(model: ClassModel, classLevel: Int, classExperience: Long): Double {
        val maxLevel = maxClassLevel(model)
        val level = classLevel.coerceIn(1, maxLevel)
        if (level >= maxLevel) {
            return 1.0
        }
        val current = totalRequiredClassExperienceForLevel(model, level)
        val next = totalRequiredClassExperienceForLevel(model, level + 1)
        val range = next - current
        if (range <= 0L) {
            return 0.0
        }
        return ((classExperience - current).coerceAtLeast(0L).toDouble() / range.toDouble())
            .coerceIn(0.0, 1.0)
    }

    /**
     * 現在クラスレベルから次のクラスレベルまでに必要な残り経験値を返します。
     * 最大レベル到達済みの場合は 0 を返します。
     *
     * @param astPlayer 対象プレイヤー
     * @return 次のレベルまでの残り累計クラス経験値
     */
    fun classExperienceRemainingToNextLevel(astPlayer: AstPlayer): Long {
        val model = classService.getLoadedClass(astPlayer.classId) ?: return 0L
        return classExperienceRemainingToNextLevel(model, astPlayer.classLevel, astPlayer.classExperience)
    }

    private fun classExperienceRemainingToNextLevel(
        model: ClassModel,
        classLevel: Int,
        classExperience: Long,
    ): Long {
        val maxLevel = maxClassLevel(model)
        val level = classLevel.coerceIn(1, maxLevel)
        if (level >= maxLevel) {
            return 0L
        }
        val nextRequired = totalRequiredClassExperienceForLevel(model, level + 1)
        return (nextRequired - classExperience).coerceAtLeast(0L)
    }

    /**
     * プレイヤー情報 GUI 向けに、読み込み済み全クラスの独立した進行度を返します。
     *
     * @param astPlayer 表示対象プレイヤー
     * @return クラスマスタ順の進行度一覧
     */
    fun getClassProgressViewEntries(astPlayer: AstPlayer): List<ClassProgressViewEntry> {
        val employedClassIds = astPlayer.getAllClassProgresses()
            .map { it.classId }
            .toSet()
        return classService.getLoadedClasses()
            .filter { model -> employedClassIds.any { it.equals(model.id, ignoreCase = true) } }
            .map { model ->
                val progress = astPlayer.getClassProgress(model.id)
                ClassProgressViewEntry(
                    id = model.id,
                    name = ColorCodeUtil.toLegacyText(model.name, model.id),
                    icon = model.icon,
                    level = progress.level,
                    experience = progress.experience,
                    experienceProgress = classExperienceProgress(model, progress.level, progress.experience),
                    experienceRemaining = classExperienceRemainingToNextLevel(model, progress.level, progress.experience),
                    current = model.id.equals(astPlayer.classId, ignoreCase = true),
                )
            }
    }

    fun getClassViewEntries(astPlayer: AstPlayer): List<ClassViewEntry> {
        val skillRegistry = AstralRecord.getInstance().skillService?.registry()
        return classService.getLoadedClasses().map { model ->
            val changeAvailability = evaluateChangeRequirements(astPlayer, model)
            ClassViewEntry(
                id = model.id,
                typeDisplay = resolveTypeDisplay(model.type),
                name = ColorCodeUtil.toLegacyText(model.name, model.id),
                description = model.description?.let { ColorCodeUtil.toLegacyText(it, "") },
                icon = model.icon,
                roleDisplay = resolveRoleDisplay(model.role),
                unlockConditions = buildUnlockConditionLines(astPlayer, model),
                changeAvailable = changeAvailability.available,
                changeBlockedReasons = changeAvailability.blockedReasons,
                baseStats = model.baseStats.map { formatStatLine(it, false) },
                growthPerLevel = model.growthPerLevel.map { formatStatLine(it, true) },
                starterSkills = model.starterSkills.map { skillId ->
                    SkillPresentationUtil.legacyName(skillRegistry?.getDefinition(skillId), skillId)
                },
                levelSkills = model.levelSkills.map { levelSkill ->
                    "&7Lv.${levelSkill.level}: &f" +
                        SkillPresentationUtil.legacyName(
                            skillRegistry?.getDefinition(levelSkill.skill),
                            levelSkill.skill
                        )
                },
            )
        }
    }

    fun canChangeClass(astPlayer: AstPlayer, classId: String): Boolean {
        val model = classService.getLoadedClass(classId) ?: return false
        return !model.commandOnly && (astPlayer.hasAdminPermission() || evaluateChangeRequirements(astPlayer, model).available)
    }

    fun getClassSuggestions(): List<String> {
        val suggestions = LinkedHashSet<String>()
        for (model in classService.getLoadedClasses()) {
            suggestions.add(model.id)

            val displayName = ColorCodeUtil.toPlainText(model.name, model.id)
            if (displayName.isNotBlank()) {
                suggestions.add(displayName)
            }
        }
        return suggestions.toList()
    }

    fun resolveLoadedClass(input: String): ClassModel? {
        val normalizedInput = normalizeLookupValue(input)
        if (normalizedInput.isEmpty()) {
            return null
        }

        classService.getLoadedClass(input)?.let { return it }

        return classService.getLoadedClasses().firstOrNull { model ->
            normalizeLookupValue(model.id) == normalizedInput
                || normalizeLookupValue(model.name) == normalizedInput
        }
    }

    fun resolveLoadedClassId(input: String): String? = resolveLoadedClass(input)?.id

    fun clearCache() = classService.clearCache()

    private fun evaluateChangeRequirements(astPlayer: AstPlayer, model: ClassModel): ChangeAvailability {
        val blockedReasons = mutableListOf<String>()
        if (model.commandOnly) {
            blockedReasons += PlayerMsgResource.getMessage(PlayerMsgId.P_5851.id)
        }
        if (model.unlockLevel > 1 && astPlayer.account.level < model.unlockLevel) {
            blockedReasons += "&e\u30d7\u30ec\u30a4\u30e4\u30fcLv.${model.unlockLevel}&7 \u304c\u5fc5\u8981\u3067\u3059"
        }

        for (requirement in model.unlockClassLevel) {
            val enoughLevel = astPlayer.getClassProgress(requirement.classId).level >= requirement.level
            if (!enoughLevel) {
                blockedReasons +=
                    "${getDisplayName(requirement.classId)} &7Lv.${requirement.level} \u304c\u5fc5\u8981\u3067\u3059"
            }
        }

        return ChangeAvailability(blockedReasons.isEmpty(), blockedReasons)
    }

    private fun buildUnlockConditionLines(astPlayer: AstPlayer, model: ClassModel): List<String> {
        val lines = mutableListOf<String>()
        if (model.unlockLevel > 1) {
            lines += "&e\u30d7\u30ec\u30a4\u30e4\u30fcLv.${model.unlockLevel}"
        }
        for (requirement in model.unlockClassLevel) {
            val enoughLevel = astPlayer.getClassProgress(requirement.classId).level >= requirement.level
            val displayName = if (!enoughLevel) {
                "&c${ColorCodeUtil.toPlainText(getDisplayName(requirement.classId), requirement.classId)}"
            } else {
                getDisplayName(requirement.classId)
            }
            lines += "$displayName &7Lv.${requirement.level}"
        }
        return lines
    }

    private fun resolveTypeDisplay(type: String): String =
        when (type.trim().uppercase(Locale.ROOT)) {
            "CLASS" -> "\u8077\u696d"
            else -> type
        }

    private fun resolveRoleDisplay(role: String): String =
        when (role.trim().uppercase(Locale.ROOT)) {
            "TANK" -> "\u30bf\u30f3\u30af"
            "DEALER" -> "\u30a2\u30bf\u30c3\u30ab\u30fc"
            "SUPPORT" -> "\u30b5\u30dd\u30fc\u30c8"
            else -> role
        }

    private fun formatStatLine(stat: ClassStat, perLevel: Boolean): String {
        val statusType = resolveStatusType(stat.status)
        val suffix = if (perLevel) " /Lv" else ""
        if (statusType != null) {
            return "${statusType.legacyColor()}${statusType.displayName} &7${statusType.formatSignedValue(stat.value)}$suffix"
        }
        return "&f${stat.status} &7${formatSignedClassStat(stat.value)}$suffix"
    }

    private fun resolveStatusType(rawStatus: String): StatusType? =
        try {
            StatusType.valueOf(rawStatus.trim().uppercase(Locale.ROOT))
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun classStatValue(stats: List<ClassStat>, statusType: StatusType): Double =
        stats.firstOrNull { stat ->
            normalizeStatusKey(stat.status) == statusType.name
        }?.value ?: 0.0

    private fun maxClassLevel(model: ClassModel): Int =
        model.maxLevel.coerceAtLeast(1)

    private fun totalRequiredClassExperienceForLevel(model: ClassModel, targetLevel: Int): Long {
        var total = 0L
        for (level in 1 until targetLevel.coerceAtLeast(1)) {
            total += requiredClassExperienceForNextLevel(model, level)
        }
        return total
    }

    private fun requiredClassExperienceForNextLevel(model: ClassModel, currentLevel: Int): Long {
        val normalizedLevel = currentLevel.coerceAtLeast(1)
        val base = 45L + normalizedLevel.toLong() * normalizedLevel.toLong() * 8L
        val milestone = if (normalizedLevel % 10 == 0) 150L + normalizedLevel * 12L else 0L
        val rate = model.expRate.coerceAtLeast(10)
        return ((base + milestone).toDouble() * rate.toDouble() / 100.0).roundToLong().coerceAtLeast(1L)
    }

    private fun normalizeStatusKey(status: String): String =
        status.trim().replace(' ', '_').replace('-', '_').uppercase(Locale.ROOT)

    private fun normalizeLookupValue(value: String): String {
        return ColorCodeUtil.toPlainText(value, value).trim().lowercase(Locale.ROOT)
    }

    private fun formatClassStat(value: Double): String {
        val longValue = value.toLong()
        return if (value == longValue.toDouble()) {
            longValue.toString()
        } else {
            "%.2f".format(Locale.ROOT, value)
        }
    }

    private fun formatSignedClassStat(value: Double): String =
        (if (value > 0.0) "+" else "") + formatClassStat(value)

    private fun persistClassProgress(astPlayer: AstPlayer) {
        persistClassProgress(astPlayer, astPlayer.classId, astPlayer.classLevel, astPlayer.classExperience)
    }

    private fun persistClassProgress(
        astPlayer: AstPlayer,
        classId: String,
        classLevel: Int,
        classExperience: Long,
    ) {
        val updated = accountService?.updateClassProgressCached(
            astPlayer.account,
            classId,
            classLevel,
            classExperience,
            astPlayer.user.uuid,
        ) ?: return
        astPlayer.account = updated
    }

    private data class ChangeAvailability(
        val available: Boolean,
        val blockedReasons: List<String>,
    )
}
