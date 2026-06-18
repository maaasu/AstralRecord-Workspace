package io.github.maaasu.astralRecord.feature.playerclass

import io.github.maaasu.astralRecord.AstralRecord
import io.github.maaasu.astralRecord.feature.`class`.model.ClassModel
import io.github.maaasu.astralRecord.feature.`class`.model.ClassStat
import io.github.maaasu.astralRecord.feature.`class`.service.ClassService
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassExperienceResult
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassViewEntry
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil
import io.github.maaasu.astralRecord.feature.status.model.StatusType
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.math.roundToLong

class PlayerClassService {
    companion object {
        const val MAX_CLASS_LEVEL = 100
    }

    private val classService = ClassService()

    fun loadAll(): Int = classService.loadAll()

    fun getLoadedClass(classId: String): ClassModel? = classService.getLoadedClass(classId)

    fun getDisplayName(classId: String): String {
        val model = classService.getLoadedClass(classId) ?: return classId
        return ColorCodeUtil.translateAlternateColorCodes(model.name)
    }

    fun getLoadedClasses(): List<ClassModel> = classService.getLoadedClasses()

    fun getStatusBonus(astPlayer: AstPlayer, statusType: StatusType): Double {
        val model = classService.getLoadedClass(astPlayer.classId) ?: return 0.0
        val base = classStatValue(model.baseStats, statusType)
        val growth = classStatValue(model.growthPerLevel, statusType) * (astPlayer.classLevel.coerceAtLeast(1) - 1)
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
        val previousLevel = astPlayer.classLevel.coerceIn(1, MAX_CLASS_LEVEL)
        if (experience <= 0) {
            return ClassExperienceResult(previousLevel, previousLevel, 0, 0)
        }
        val model = classService.getLoadedClass(astPlayer.classId)
            ?: return ClassExperienceResult(previousLevel, previousLevel, 0, 0)

        val totalExperience = (astPlayer.classExperience + experience).coerceAtLeast(0L)
        var level = previousLevel
        while (level < MAX_CLASS_LEVEL && totalExperience >= totalRequiredClassExperienceForLevel(model, level + 1)) {
            level++
        }
        astPlayer.classExperience = totalExperience
        astPlayer.classLevel = level
        return ClassExperienceResult(previousLevel, level, experience, (level - previousLevel).coerceAtLeast(0))
    }

    /**
     * 現在クラスレベル内の経験値進捗率を返します。
     *
     * @param astPlayer 対象プレイヤー
     * @return 0.0 から 1.0 の進捗率
     */
    fun classExperienceProgress(astPlayer: AstPlayer): Double {
        val model = classService.getLoadedClass(astPlayer.classId) ?: return 0.0
        val level = astPlayer.classLevel.coerceIn(1, MAX_CLASS_LEVEL)
        if (level >= MAX_CLASS_LEVEL) {
            return 1.0
        }
        val current = totalRequiredClassExperienceForLevel(model, level)
        val next = totalRequiredClassExperienceForLevel(model, level + 1)
        val range = next - current
        if (range <= 0L) {
            return 0.0
        }
        return ((astPlayer.classExperience - current).coerceAtLeast(0L).toDouble() / range.toDouble())
            .coerceIn(0.0, 1.0)
    }

    fun getClassViewEntries(astPlayer: AstPlayer): List<ClassViewEntry> {
        val skillRegistry = AstralRecord.getInstance().skillService?.registry()
        return classService.getLoadedClasses().map { model ->
            val changeAvailability = evaluateChangeAvailability(astPlayer, model)
            ClassViewEntry(
                id = model.id,
                typeDisplay = resolveTypeDisplay(model.type),
                name = ColorCodeUtil.translateAlternateColorCodes(model.name),
                description = model.description?.let(ColorCodeUtil::translateAlternateColorCodes),
                icon = model.icon,
                roleDisplay = resolveRoleDisplay(model.role),
                unlockConditions = buildUnlockConditionLines(model),
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
        return evaluateChangeAvailability(astPlayer, model).available
    }

    fun getClassSuggestions(): List<String> {
        val suggestions = LinkedHashSet<String>()
        for (model in classService.getLoadedClasses()) {
            suggestions.add(model.id)

            val displayName = ColorCodeUtil.stripColor(
                ColorCodeUtil.translateAlternateColorCodes(model.name)
            )
            if (!displayName.isNullOrBlank()) {
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

    private fun evaluateChangeAvailability(astPlayer: AstPlayer, model: ClassModel): ChangeAvailability {
        if (astPlayer.hasAdminPermission()) {
            return ChangeAvailability(true, emptyList())
        }

        val blockedReasons = mutableListOf<String>()
        if (model.unlockLevel > 1 && astPlayer.account.level < model.unlockLevel) {
            blockedReasons += "&e\u30d7\u30ec\u30a4\u30e4\u30fcLv.${model.unlockLevel}&7 \u304c\u5fc5\u8981\u3067\u3059"
        }

        for (requirement in model.unlockClassLevel) {
            val sameClass = astPlayer.classId.equals(requirement.classId, ignoreCase = true)
            val enoughLevel = astPlayer.classLevel >= requirement.level
            if (!sameClass || !enoughLevel) {
                blockedReasons +=
                    "${getDisplayName(requirement.classId)} &7Lv.${requirement.level} \u304c\u5fc5\u8981\u3067\u3059"
            }
        }

        return ChangeAvailability(blockedReasons.isEmpty(), blockedReasons)
    }

    private fun buildUnlockConditionLines(model: ClassModel): List<String> {
        val lines = mutableListOf<String>()
        if (model.unlockLevel > 1) {
            lines += "&e\u30d7\u30ec\u30a4\u30e4\u30fcLv.${model.unlockLevel}"
        }
        for (requirement in model.unlockClassLevel) {
            lines += "${getDisplayName(requirement.classId)} &7Lv.${requirement.level}"
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
        val translated = ColorCodeUtil.translateAlternateColorCodes(value)
        val stripped = ColorCodeUtil.stripColor(translated)
        return stripped?.trim()?.lowercase(Locale.ROOT).orEmpty()
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

    private data class ChangeAvailability(
        val available: Boolean,
        val blockedReasons: List<String>,
    )
}
