package io.github.maaasu.astralRecord.feature.item.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType
import io.github.maaasu.astralRecord.feature.item.model.SetEffect
import io.github.maaasu.astralRecord.feature.item.model.SetEffectPiece
import io.github.maaasu.astralRecord.feature.item.model.SetEffectStat
import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil
import java.io.IOException
import java.net.URLEncoder
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * AstralRecord API を通じてセット効果定義を取得するリポジトリ。
 */
class SetEffectRepository {

    /**
     * 全セット効果一覧を取得します。
     * GET /api/seteffect
     */
    fun findAll(): List<SetEffect> {
        val path = "/api/seteffect"
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> parseSetEffectList(response.body())
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
     * 指定 ID のセット効果を取得します。
     * GET /api/seteffect/{setId}
     */
    fun findById(setId: String): SetEffect? {
        val encodedSetId = URLEncoder.encode(setId.trim(), StandardCharsets.UTF_8).replace("+", "%20")
        val path = "/api/seteffect/$encodedSetId"

        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> {
                        parseSetEffect(response.body())
                    }
                    404 -> {
                        Logger.log(LogId.W_5400, setId)
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
            Logger.error(LogId.E_5400, e, e.message ?: e.javaClass.simpleName)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.error(LogId.E_5400, e, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private fun parseSetEffectList(json: String): List<SetEffect> {
        val array = JsonParser.parseString(json).asJsonArray
        return array.mapNotNull { element ->
            if (!element.isJsonObject) null else parseSetEffect(element.asJsonObject)
        }
    }

    private fun parseSetEffect(json: String): SetEffect {
        return parseSetEffect(JsonParser.parseString(json).asJsonObject)
    }

    private fun parseSetEffect(obj: JsonObject): SetEffect {
        val piecesArray = obj.getAsJsonArray("pieces") ?: JsonArray()
        val pieces = piecesArray.mapNotNull { element ->
            if (!element.isJsonObject) null else parsePiece(element.asJsonObject)
        }

        return SetEffect(
            id = obj.get("id").asString,
            name = obj.get("name").asString,
            pieces = pieces,
        )
    }

    private fun parsePiece(obj: JsonObject): SetEffectPiece {
        val statsArray = obj.getAsJsonArray("stats") ?: JsonArray()
        val stats = statsArray.mapNotNull { element ->
            if (!element.isJsonObject) null else parseStat(element.asJsonObject)
        }

        val skillsArray = obj.getAsJsonArray("skills") ?: JsonArray()
        val skills = skillsArray.mapNotNull { e ->
            if (e.isJsonPrimitive) e.asString else null
        }

        return SetEffectPiece(
            count = obj.get("count")?.asInt ?: 0,
            stats = stats,
            skills = skills,
        )
    }

    private fun parseStat(obj: JsonObject): SetEffectStat? {
        val status = obj.get("status")?.takeIf { !it.isJsonNull }?.asString ?: return null
        val value = obj.get("value")?.takeIf { !it.isJsonNull }?.asString ?: return null
        return SetEffectStat(
            status = status,
            type = ItemEquipmentStatType.fromApiValue(obj.get("type")?.takeIf { !it.isJsonNull }?.asString),
            value = value,
        )
    }
}
