package io.github.maaasu.astralRecord.feature.skill.repository

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset
import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil
import java.io.IOException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * スキルバインドプリセット API と通信する repository です。
 */
class SkillBindPresetRepository {

    fun findByAccountId(accountId: UUID): List<SkillBindPreset> {
        val path = "/api/skill-bind-presets?account_id=$accountId"
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> JsonParser.parseString(response.body()).asJsonArray.map { parsePreset(it.asJsonObject, accountId) }
                    else -> {
                        val message = "HTTP ${response.statusCode()} for GET $path"
                        Logger.log(LogId.E_5801, message)
                        throw IOException(message)
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5801, e, e.message ?: "Interrupted while GET $path")
            throw RuntimeException(e)
        }
    }

    fun save(
        accountId: UUID,
        presetIndex: Int,
        activeSkillSlots: List<String?>,
        passiveSkillSlots: List<String?>,
        updatedBy: UUID,
    ): SkillBindPreset {
        val path = "/api/skill-bind-presets/$accountId/$presetIndex"
        val body = ApiRequestUtil.buildJsonBody {
            add("activeSkillSlots", toJsonArray(activeSkillSlots))
            add("passiveSkillSlots", toJsonArray(passiveSkillSlots))
            addProperty("updatedBy", updatedBy.toString())
        }
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> parsePreset(JsonParser.parseString(response.body()).asJsonObject, accountId)
                    else -> {
                        val message = "HTTP ${response.statusCode()} for PUT $path"
                        Logger.log(LogId.E_5801, message)
                        throw IOException(message)
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5801, e, e.message ?: "Interrupted while PUT $path")
            throw RuntimeException(e)
        }
    }

    private fun parsePreset(obj: JsonObject, fallbackAccountId: UUID): SkillBindPreset {
        val presetIdElement = obj.get("skillBindPresetId")
        val presetId = if (presetIdElement == null || presetIdElement.isJsonNull) null else UUID.fromString(presetIdElement.asString)
        val accountIdElement = obj.get("accountId")
        val accountId = if (accountIdElement == null || accountIdElement.isJsonNull) fallbackAccountId else UUID.fromString(accountIdElement.asString)
        return SkillBindPreset(
            presetId,
            accountId,
            obj.get("presetIndex").asInt,
            parseSlots(obj.getAsJsonArray("activeSkillSlots")),
            parseSlots(obj.getAsJsonArray("passiveSkillSlots")),
            obj.get("isUnlocked")?.asBoolean ?: false,
            obj.get("isSaved")?.asBoolean ?: false,
            obj.get("version")?.asInt ?: 0,
        )
    }

    private fun parseSlots(array: JsonArray?): List<String?> {
        val result = mutableListOf<String?>()
        if (array != null) {
            for (element in array) {
                result += if (element == null || element.isJsonNull) null else element.asString
            }
        }
        while (result.size < SkillBindPreset.SLOT_COUNT) {
            result += null
        }
        return result.take(SkillBindPreset.SLOT_COUNT)
    }

    private fun toJsonArray(values: List<String?>): JsonArray {
        val array = JsonArray()
        for (index in 0 until SkillBindPreset.SLOT_COUNT) {
            val value = values.getOrNull(index)
            if (value.isNullOrBlank()) {
                array.add(JsonNull.INSTANCE)
            } else {
                array.add(value)
            }
        }
        return array
    }
}
