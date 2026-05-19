package io.github.maaasu.astralRecord.feature.buff.repository

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.buff.model.BuffModifier
import io.github.maaasu.astralRecord.feature.buff.model.BuffModifierType
import io.github.maaasu.astralRecord.feature.buff.model.BuffType
import io.github.maaasu.astralRecord.feature.status.model.StatusType
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil
import java.io.IOException
import java.net.URLEncoder
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * AstralRecord API を通じてバフ定義を取得するリポジトリです。
 */
class BuffRepository {

    /**
     * バフIDで定義を取得します。
     *
     * @param buffId バフID
     * @return 定義。存在しない場合は null
     */
    fun findById(buffId: String): BuffType? {
        val encoded = URLEncoder.encode(buffId, StandardCharsets.UTF_8).replace("+", "%20")
        val path = "/api/buff/$encoded"

        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> parseBuffType(response.body())
                    404 -> null
                    else -> throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }
    }

    private fun parseBuffType(json: String): BuffType {
        val obj = JsonParser.parseString(json).asJsonObject
        val modifiersArray = obj.getAsJsonArray("modifiers") ?: JsonArray()
        return BuffType(
            id = obj.get("id").asString,
            type = obj.get("type").asString,
            displayName = obj.get("name").asString,
            durationTicks = obj.get("durationTicks").asInt,
            isDebuff = obj.get("isDebuff").asBoolean,
            modifiers = parseModifiers(modifiersArray),
        )
    }

    private fun parseModifiers(array: JsonArray): List<BuffModifier> {
        val result = mutableListOf<BuffModifier>()
        for (element in array) {
            if (!element.isJsonObject) {
                continue
            }

            val obj = element.asJsonObject
            val status = obj.get("status")?.asString ?: continue
            val statusType = parseStatusType(status) ?: continue

            result += BuffModifier(
                status = statusType,
                type = BuffModifierType.fromApiValue(obj.get("type")?.asString ?: "FLAT"),
                value = obj.get("value")?.asDouble ?: 0.0,
            )
        }

        return result
    }

    private fun parseStatusType(value: String): StatusType? {
        return try {
            StatusType.valueOf(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

