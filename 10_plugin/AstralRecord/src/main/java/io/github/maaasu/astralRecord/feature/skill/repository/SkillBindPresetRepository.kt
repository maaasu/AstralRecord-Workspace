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
                        Logger.log(LogId.E_5803, message)
                        throw IOException(message)
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.error(LogId.E_5803, e, e.message ?: e.javaClass.simpleName)
            throw RuntimeException(e)
        }
    }

    fun save(
        accountId: UUID,
        presetIndex: Int,
        activeSkillSlots: List<String?>,
        leftClickSkillId: String?,
        passiveSkillSlots: List<String?>,
        updatedBy: UUID,
    ): SkillBindPreset {
        val path = "/api/skill-bind-presets/$accountId/$presetIndex"
        val body = ApiRequestUtil.buildJsonBody {
            add("activeSkillSlots", toJsonArray(activeSkillSlots, SkillBindPreset.ACTION_RING_SLOT_COUNT))
            if (leftClickSkillId.isNullOrBlank()) {
                add("leftClickSkillId", JsonNull.INSTANCE)
            } else {
                addProperty("leftClickSkillId", leftClickSkillId.trim())
            }
            add("passiveSkillSlots", toJsonArray(passiveSkillSlots, SkillBindPreset.PASSIVE_SLOT_COUNT))
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
                        Logger.log(LogId.E_5804, message)
                        throw IOException(message)
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.error(LogId.E_5804, e, e.message ?: e.javaClass.simpleName)
            throw RuntimeException(e)
        }
    }

    fun save(
        accountId: UUID,
        presetIndex: Int,
        activeSkillSlots: List<String?>,
        passiveSkillSlots: List<String?>,
        updatedBy: UUID,
    ): SkillBindPreset = save(
        accountId,
        presetIndex,
        activeSkillSlots,
        SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID,
        passiveSkillSlots,
        updatedBy,
    )

    private fun parsePreset(obj: JsonObject, fallbackAccountId: UUID): SkillBindPreset {
        val presetIdElement = obj.get("skillBindPresetId")
        val presetId = if (presetIdElement == null || presetIdElement.isJsonNull) null else UUID.fromString(presetIdElement.asString)
        val accountIdElement = obj.get("accountId")
        val accountId = if (accountIdElement == null || accountIdElement.isJsonNull) fallbackAccountId else UUID.fromString(accountIdElement.asString)
        return SkillBindPreset(
            presetId,
            accountId,
            obj.get("presetIndex").asInt,
            parseActionRingSlots(obj.getAsJsonArray("activeSkillSlots")),
            obj.get("leftClickSkillId")?.takeUnless { it.isJsonNull }?.asString,
            parsePassiveSlots(obj.getAsJsonArray("passiveSkillSlots")),
            obj.get("isUnlocked")?.asBoolean ?: false,
            obj.get("isSaved")?.asBoolean ?: false,
            obj.get("version")?.asInt ?: 0,
        )
    }

    private fun parseActionRingSlots(array: JsonArray?): List<String?> {
        return parseSlots(array, SkillBindPreset.ACTION_RING_SLOT_COUNT)
    }

    private fun parsePassiveSlots(array: JsonArray?): List<String?> {
        return parseSlots(array, SkillBindPreset.PASSIVE_SLOT_COUNT)
    }

    private fun parseSlots(array: JsonArray?, slotCount: Int): List<String?> {
        val result = mutableListOf<String?>()
        if (array != null) {
            for (element in array) {
                result += if (element == null || element.isJsonNull) null else element.asString
            }
        }
        while (result.size < slotCount) {
            result += null
        }
        return result.take(slotCount)
    }

    private fun toJsonArray(values: List<String?>, slotCount: Int): JsonArray {
        val array = JsonArray()
        for (index in 0 until slotCount) {
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
