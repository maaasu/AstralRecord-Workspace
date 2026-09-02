package io.github.maaasu.astralRecord.feature.skill.repository

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType
import io.github.maaasu.astralRecord.feature.skill.model.SkillLevelDefinition
import io.github.maaasu.astralRecord.feature.skill.model.SkillSigilSlotDefinition
import io.github.maaasu.astralRecord.feature.skill.model.SkillRequiredItemDefinition
import io.github.maaasu.astralRecord.feature.skill.model.SkillStatusModifierDefinition
import io.github.maaasu.astralRecord.feature.skill.model.SkillSummary
import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil
import java.io.IOException
import java.net.URLEncoder
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * AstralRecord API を通じてスキル定義を取得するリポジトリ。
 *
 * GET `/api/skill`：一覧（[SkillSummary]）、GET `/api/skill/{skillId}`：詳細（[SkillDefinition]）。
 */
class SkillRepository {

    /**
     * 全スキルの一覧を取得します。
     * GET /api/skill
     *
     * @return スキル一覧
     */
    fun findAll(): List<SkillSummary> {
        val path = "/api/skill"
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> parseSummaryList(response.body())
                    else -> {
                        Logger.log(LogId.E_5801, "HTTP ${response.statusCode()} for GET $path")
                        throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5801, e, e.message ?: "Interrupted while GET $path")
            throw RuntimeException(e)
        }
    }

    /**
     * スキル ID でスキル定義を取得します。
     * GET /api/skill/{skillId}
     *
     * @param skillId スキル ID
     * @return スキル定義。存在しない場合は null
     */
    fun findById(skillId: String): SkillDefinition? {
        val encoded = URLEncoder.encode(skillId, StandardCharsets.UTF_8).replace("+", "%20")
        val path = "/api/skill/$encoded"

        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> {
                        val definition = toDefinition(JsonParser.parseString(response.body()).asJsonObject)
                        Logger.log(LogId.D_5800, skillId)
                        definition
                    }
                    404 -> {
                        Logger.log(LogId.W_5800, skillId)
                        null
                    }
                    else -> {
                        Logger.log(LogId.E_5800, "HTTP ${response.statusCode()} for GET $path")
                        throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5800, e, e.message ?: "Interrupted while GET $path")
            throw RuntimeException(e)
        }
    }

    /**
     * API レスポンスオブジェクトを [SkillDefinition] へ変換します。
     * `onCast.sound` は `onCastSound` へ射影します。
     * `resourceType` / `resourceCost` は top-level に指定された値だけを読み取り、
     * 旧 `params` / `manaCost` からの互換解決と、ENERGY主消費に併記した副MP消費の解決は
     * [io.github.maaasu.astralRecord.feature.skill.service.SkillService] に委ねます。
     */
    private fun toDefinition(obj: JsonObject): SkillDefinition {
        val onCastObj = parseObjectOrNull(obj, "onCast")
        val onCastSound = onCastObj?.get("sound")?.takeIf { !it.isJsonNull }?.asString
        val params = parseParams(parseObjectOrNull(obj, "params"))

        return SkillDefinition(
            id = obj.get("id").asString,
            implementationId = obj.get("implementationId").asString,
            name = obj.get("name").asString,
            description = obj.get("description")?.takeIf { !it.isJsonNull }?.asString,
            icon = obj.get("icon")?.takeIf { !it.isJsonNull }?.asString,
            lore = parseStringList(obj.getAsJsonArray("lore")),
            cooldownTicks = obj.get("cooldownTicks")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
            manaCost = obj.get("manaCost")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0,
            castTimeTicks = obj.get("castTimeTicks")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
            requiredLevel = obj.get("requiredLevel")?.takeIf { !it.isJsonNull }?.asInt ?: 1,
            onCastSound = onCastSound,
            params = params,
            tags = parseStringList(obj.getAsJsonArray("tags")),
            passiveBindRequired = parsePassiveBindRequired(parseObjectOrNull(obj, "passive")),
            resourceType = parseResourceTypeOrNull(obj.get("resourceType")),
            resourceCost = parseResourceCostOrNull(obj.get("resourceCost")),
            cooldownId = obj.get("cooldownId")?.takeIf { !it.isJsonNull }?.asString,
            maxLevel = obj.get("maxLevel")?.takeIf { !it.isJsonNull }?.asInt ?: 1,
            levels = parseLevels(obj.getAsJsonArray("levels")),
            sigilSlotsByLevel = parseSigilSlots(obj.getAsJsonArray("sigilSlotsByLevel")),
            allowedSigilIds = parseStringList(obj.getAsJsonArray("allowedSigilIds")),
            learnRequiredItems = parseRequiredItems(obj.getAsJsonArray("learnRequiredItems")),
            levelUpRequiredItems = parseRequiredItems(obj.getAsJsonArray("levelUpRequiredItems")),
        )
    }

    private fun parseRequiredItems(array: JsonArray?): List<SkillRequiredItemDefinition> {
        if (array == null) return emptyList()
        return array.filter { it.isJsonObject }.map { element ->
            val obj = element.asJsonObject
            SkillRequiredItemDefinition(
                itemId = obj.get("itemId")?.asString?.trim().orEmpty(),
                amount = obj.get("amount")?.asInt ?: 1,
            )
        }.filter { it.itemId.isNotBlank() && it.amount > 0 }
    }

    private fun parseLevels(array: JsonArray?): List<SkillLevelDefinition> {
        if (array == null) return emptyList()
        return array
            .filter { it.isJsonObject }
            .map { element ->
                val obj = element.asJsonObject
                SkillLevelDefinition(
                    level = obj.get("level")?.asInt ?: 1,
                    cooldownTicksDelta = obj.get("cooldownTicksDelta")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                    resourceCostDelta = obj.get("resourceCostDelta")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0,
                    castTimeTicksDelta = obj.get("castTimeTicksDelta")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                    paramDeltas = parseDoubleMap(obj.getAsJsonObject("paramDeltas")),
                    statusModifiers = parseStatusModifiers(obj.getAsJsonArray("statusModifiers")),
                )
            }
            .sortedBy { it.level }
    }

    private fun parseSigilSlots(array: JsonArray?): List<SkillSigilSlotDefinition> {
        if (array == null) return emptyList()
        return array
            .filter { it.isJsonObject }
            .map { element ->
                val obj = element.asJsonObject
                SkillSigilSlotDefinition(
                    level = obj.get("level")?.asInt ?: 1,
                    slots = obj.get("slots")?.asInt ?: 0,
                )
            }
            .sortedBy { it.level }
    }

    private fun parseStatusModifiers(array: JsonArray?): List<SkillStatusModifierDefinition> {
        if (array == null) return emptyList()
        return array
            .filter { it.isJsonObject }
            .map { element ->
                val obj = element.asJsonObject
                SkillStatusModifierDefinition(
                    status = obj.get("status").asString,
                    value = obj.get("value")?.asDouble ?: 0.0,
                )
            }
    }

    private fun parseDoubleMap(obj: JsonObject?): Map<String, Double> {
        if (obj == null) return emptyMap()
        return obj.entrySet().associate { (key, value) -> key to value.asDouble }
    }

    private fun parseResourceTypeOrNull(element: JsonElement?): SkillResourceType? {
        if (element == null || element.isJsonNull) return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw SkillParameterException("resourceType", "MANA または ENERGY を文字列で指定してください")
        }

        val rawValue = element.asString.trim()
        if (rawValue.isEmpty()) {
            throw SkillParameterException("resourceType", "MANA または ENERGY を指定してください")
        }
        return try {
            SkillResourceType.valueOf(rawValue.uppercase(Locale.ROOT))
        } catch (e: IllegalArgumentException) {
            throw SkillParameterException("resourceType", "MANA または ENERGY を指定してください")
        }
    }

    private fun parseResourceCostOrNull(element: JsonElement?): Double? {
        if (element == null || element.isJsonNull) return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            throw SkillParameterException("resourceCost", "number を指定してください")
        }
        return element.asDouble
    }

    private fun parsePassiveBindRequired(obj: JsonObject?): Boolean {
        if (obj == null) return true
        val bindRequired = obj.get("bindRequired") ?: return true
        return bindRequired.takeIf { !it.isJsonNull }?.asBoolean ?: true
    }

    private fun parseSummaryList(json: String): List<SkillSummary> {
        val array = JsonParser.parseString(json).asJsonArray
        val result = mutableListOf<SkillSummary>()
        for (element in array) {
            if (!element.isJsonObject) continue
            val obj = element.asJsonObject
            result += SkillSummary(
                id = obj.get("id").asString,
                name = obj.get("name").asString,
                implementationId = obj.get("implementationId").asString,
                icon = obj.get("icon")?.takeIf { !it.isJsonNull }?.asString,
                tags = parseStringList(obj.getAsJsonArray("tags")),
            )
        }
        return result
    }

    private fun parseStringList(array: JsonArray?): List<String> {
        if (array == null) return emptyList()
        val result = mutableListOf<String>()
        for (element in array) {
            if (element.isJsonPrimitive) {
                result += element.asString
            }
        }
        return result
    }

    private fun parseParams(obj: JsonObject?): Map<String, Any> {
        if (obj == null) return emptyMap()
        val result = LinkedHashMap<String, Any>()
        for ((key, value) in obj.entrySet()) {
            val converted = convertJsonValue(value) ?: continue
            result[key] = converted
        }
        return result
    }

    private fun convertJsonValue(element: JsonElement): Any? {
        if (element.isJsonNull) return null
        return when {
            element.isJsonPrimitive -> {
                val primitive = element.asJsonPrimitive
                when {
                    primitive.isBoolean -> primitive.asBoolean
                    primitive.isNumber -> primitive.asNumber
                    else -> primitive.asString
                }
            }
            element.isJsonArray -> {
                val list = mutableListOf<Any>()
                for (child in element.asJsonArray) {
                    val converted = convertJsonValue(child) ?: continue
                    list += converted
                }
                list
            }
            element.isJsonObject -> {
                val nested = LinkedHashMap<String, Any>()
                for ((k, v) in element.asJsonObject.entrySet()) {
                    val converted = convertJsonValue(v) ?: continue
                    nested[k] = converted
                }
                nested
            }
            else -> null
        }
    }

    private fun parseObjectOrNull(obj: JsonObject, key: String): JsonObject? {
        val element = obj.get(key) ?: return null
        if (element.isJsonNull || !element.isJsonObject) return null
        return element.asJsonObject
    }
}
