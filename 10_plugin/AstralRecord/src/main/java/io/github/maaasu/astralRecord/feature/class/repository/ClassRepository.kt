package io.github.maaasu.astralRecord.feature.`class`.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.`class`.model.ClassModel
import io.github.maaasu.astralRecord.feature.`class`.model.ClassStat
import io.github.maaasu.astralRecord.feature.`class`.model.ClassSummary
import io.github.maaasu.astralRecord.feature.`class`.model.ClassUnlockClassLevel
import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil
import java.io.IOException
import java.net.URLEncoder
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * AstralRecord API を通じてクラス定義を取得するリポジトリ。
 */
class ClassRepository {

    /**
     * 全クラスの一覧（id/name/role）を取得します。
     * GET /api/class
     */
    fun findAll(): List<ClassSummary> {
        val path = "/api/class"
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> parseSummaryList(response.body())
                    else -> {
                        val message = "HTTP ${response.statusCode()} for GET $path"
                        Logger.log(LogId.E_5501, message)
                        throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5501, e, e.message ?: "Interrupted while GET $path")
            throw RuntimeException(e)
        }
    }

    /**
     * 指定IDのクラス定義を取得します。
     * GET /api/class/{classId}
     */
    fun findById(classId: String): ClassModel? {
        val encodedClassId = URLEncoder.encode(classId, StandardCharsets.UTF_8).replace("+", "%20")
        val path = "/api/class/$encodedClassId"

        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> {
                        val model = parseClass(response.body())
                        Logger.log(LogId.D_5500, classId)
                        model
                    }
                    404 -> {
                        Logger.log(LogId.W_5500, classId)
                        null
                    }
                    else -> {
                        Logger.log(LogId.E_5500, "HTTP ${response.statusCode()} for GET $path")
                        throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5500, e, e.message ?: "Interrupted while GET $path")
            throw RuntimeException(e)
        }
    }

    private fun parseClass(json: String): ClassModel {
        val obj = JsonParser.parseString(json).asJsonObject
        return parseClass(obj)
    }

    private fun parseSummaryList(json: String): List<ClassSummary> {
        val array = JsonParser.parseString(json).asJsonArray
        return array.map { element ->
            val obj = element.asJsonObject
            ClassSummary(
                id = obj.get("id").asString,
                name = obj.get("name").asString,
                shortName = obj.get("shortName").asString,
                role = obj.get("role").asString,
            )
        }
    }

    private fun parseClass(obj: JsonObject): ClassModel {
        return ClassModel(
            schemaVersion = obj.get("schemaVersion").asInt,
            id = obj.get("id").asString,
            type = obj.get("type").asString,
            name = obj.get("name").asString,
            shortName = obj.get("shortName").asString,
            description = parseStringOrNull(obj, "description"),
            icon = parseStringOrNull(obj, "icon"),
            role = obj.get("role").asString,
            maxLevel = obj.get("maxLevel")?.asInt ?: 100,
            commandOnly = obj.get("commandOnly")?.asBoolean ?: false,
            unlockLevel = obj.get("unlockLevel")?.asInt ?: 1,
            unlockClassLevel = parseUnlockClassLevelList(obj.getAsJsonArray("unlockClassLevel")),
            baseStats = parseStatList(obj.getAsJsonArray("baseStats")),
            growthPerLevel = parseStatList(obj.getAsJsonArray("growthPerLevel")),
            expRate = obj.get("expRate")?.asInt ?: 100,
            usableSkills = parseStringList(obj.getAsJsonArray("usableSkills")),
            tags = parseStringList(obj.getAsJsonArray("tags")),
        )
    }

    private fun parseUnlockClassLevelList(array: JsonArray?): List<ClassUnlockClassLevel> {
        if (array == null || array.isJsonNull) return emptyList()
        return array.mapNotNull { element ->
            val obj = element.asJsonObject
            val classId = parseStringOrNull(obj, "classId")
                ?: parseStringOrNull(obj, "class")
                ?: return@mapNotNull null
            val level = obj.get("level")?.takeUnless { it.isJsonNull }?.asInt ?: return@mapNotNull null

            ClassUnlockClassLevel(
                classId = classId,
                level = level,
            )
        }
    }

    private fun parseStatList(array: JsonArray?): List<ClassStat> {
        if (array == null || array.isJsonNull) return emptyList()
        return array.map { element ->
            val obj = element.asJsonObject
            ClassStat(
                status = obj.get("status").asString,
                value = obj.get("value").asDouble,
            )
        }
    }

    private fun parseStringList(array: JsonArray?): List<String> {
        if (array == null || array.isJsonNull) return emptyList()
        return array.map { it.asString }
    }

    private fun parseStringOrNull(obj: JsonObject, key: String): String? {
        if (!obj.has(key)) return null
        val element = obj.get(key)
        if (element == null || element.isJsonNull) return null
        return element.asString
    }
}
