package io.github.maaasu.astralRecord.feature.skill.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset
import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil
import java.io.IOException
import java.net.http.HttpResponse
import java.util.UUID

/** バインドプリセットの初期読込を行う。更新はプレイヤー状態スナップショットへ集約する。 */
class SkillBindPresetRepository {
    fun findByAccountId(accountId: UUID): List<SkillBindPreset> {
        val path = "/api/skill-bind-presets?account_id=$accountId"
        try {
            ApiRequestUtil.sharedClient().let { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> JsonParser.parseString(response.body()).asJsonArray.map {
                        parsePreset(it.asJsonObject, accountId)
                    }
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

    private fun parsePreset(obj: JsonObject, fallbackAccountId: UUID): SkillBindPreset {
        val presetIdElement = obj.get("skillBindPresetId")
        val presetId = if (presetIdElement == null || presetIdElement.isJsonNull) {
            null
        } else {
            UUID.fromString(presetIdElement.asString)
        }
        val accountIdElement = obj.get("accountId")
        val accountId = if (accountIdElement == null || accountIdElement.isJsonNull) {
            fallbackAccountId
        } else {
            UUID.fromString(accountIdElement.asString)
        }
        return SkillBindPreset(
            presetId,
            accountId,
            obj.get("presetIndex").asInt,
            parseSlots(obj.getAsJsonArray("activeSkillSlots"), SkillBindPreset.ACTION_RING_SLOT_COUNT),
            obj.get("leftClickSkillId")?.takeUnless { it.isJsonNull }?.asString,
            parseSlots(obj.getAsJsonArray("passiveSkillSlots"), SkillBindPreset.PASSIVE_SLOT_COUNT),
            obj.get("isUnlocked")?.asBoolean ?: false,
            obj.get("isSaved")?.asBoolean ?: false,
            obj.get("version")?.asInt ?: 0,
            obj.get("isSelected")?.asBoolean ?: false,
        )
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
}
