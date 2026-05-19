package io.github.maaasu.astralRecord.feature.skill.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.skill.model.SkillModel
import io.github.maaasu.astralRecord.feature.skill.model.SkillOnCast
import io.github.maaasu.astralRecord.feature.skill.model.SkillSummary
import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil
import java.io.IOException
import java.net.URLEncoder
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * AstralRecord API を通じてスキル定義を取得するリポジトリ。
 */
class SkillRepository {

    /**
     * 全スキルの一覧（id/name/implementationId）を取得します。
     * GET /api/skill
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
                        Logger.log(LogId.E_5401, "HTTP ${response.statusCode()} for GET $path")
                        throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5401, e, e.message ?: "Interrupted while GET $path")
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
    fun findById(skillId: String): SkillModel? {
        val encoded = URLEncoder.encode(skillId, StandardCharsets.UTF_8).replace("+", "%20")
        val path = "/api/skill/$encoded"

        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> {
                        val skill = parseSkill(response.body())
                        Logger.log(LogId.D_5400, skillId)
                        skill
                    }
                    404 -> {
                        Logger.log(LogId.W_5400, skillId)
                        null
                    }
                    else -> {
                        Logger.log(LogId.E_5400, "HTTP ${response.statusCode()} for GET $path")
                        throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5400, e, e.message ?: "Interrupted while GET $path")
            throw RuntimeException(e)
        }
    }

    private fun parseSkill(json: String): SkillModel {
        val obj = JsonParser.parseString(json).asJsonObject
        return parseSkill(obj)
    }

    private fun parseSkill(obj: JsonObject): SkillModel {
        val onCastObj = obj.getAsJsonObject("onCast")
        val onCast = if (onCastObj != null) {
            SkillOnCast(
                sound = onCastObj.get("sound")?.takeIf { !it.isJsonNull }?.asString,
            )
        } else {
            null
        }

        return SkillModel(
            schemaVersion = obj.get("schemaVersion")?.asInt ?: 1,
            id = obj.get("id").asString,
            type = obj.get("type")?.asString ?: "SKILL",
            implementationId = obj.get("implementationId").asString,
            name = obj.get("name").asString,
            description = obj.get("description")?.takeIf { !it.isJsonNull }?.asString,
            icon = obj.get("icon")?.takeIf { !it.isJsonNull }?.asString,
            lore = parseStringList(obj.getAsJsonArray("lore")),
            cooldownTicks = obj.get("cooldownTicks")?.asLong ?: 0L,
            manaCost = obj.get("manaCost")?.asDouble ?: 0.0,
            castTimeTicks = obj.get("castTimeTicks")?.asLong ?: 0L,
            requiredLevel = obj.get("requiredLevel")?.asInt ?: 1,
            onCast = onCast,
            params = parseParams(obj.getAsJsonObject("params")),
            tags = parseStringList(obj.getAsJsonArray("tags")),
        )
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
        val result = mutableMapOf<String, Any>()
        for ((key, value) in obj.entrySet()) {
            if (value.isJsonNull) continue
            when {
                value.isJsonPrimitive -> {
                    val primitive = value.asJsonPrimitive
                    result[key] = when {
                        primitive.isBoolean -> primitive.asBoolean
                        primitive.isNumber -> primitive.asNumber
                        else -> primitive.asString
                    }
                }
                value.isJsonObject -> result[key] = value.asJsonObject.toString()
                value.isJsonArray -> result[key] = value.asJsonArray.toString()
            }
        }
        return result
    }
}
