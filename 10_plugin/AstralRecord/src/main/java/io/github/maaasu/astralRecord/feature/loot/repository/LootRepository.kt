package io.github.maaasu.astralRecord.feature.loot.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.loot.model.LootEntry
import io.github.maaasu.astralRecord.feature.loot.model.LootModel
import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil
import java.io.IOException
import java.net.URLEncoder
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * AstralRecord API を通じてルートテーブル定義を取得するリポジトリ。
 */
class LootRepository {

    /**
     * 全ルートテーブル一覧を取得します。
     * GET /api/loot/table
     */
    fun findAll(): List<LootModel> {
        val path = "/api/loot/table"
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> parseLootList(response.body())
                    else -> {
                        Logger.log(LogId.E_5300, "HTTP ${response.statusCode()} for GET $path")
                        throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5300, e, e.message ?: "Interrupted while GET $path")
            throw RuntimeException(e)
        }
    }

    /**
     * 指定IDのルートテーブルを取得します。
     * GET /api/loot/table/{tableId}
     */
    fun findById(lootId: String): LootModel? {
        val encodedId = URLEncoder.encode(lootId.trim(), StandardCharsets.UTF_8).replace("+", "%20")
        val path = "/api/loot/table/$encodedId"

        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> {
                        val loot = parseLoot(response.body())
                        Logger.log(LogId.D_5300, lootId)
                        loot
                    }
                    404 -> {
                        Logger.log(LogId.W_5300, lootId)
                        null
                    }
                    else -> {
                        Logger.log(LogId.E_5300, "HTTP ${response.statusCode()} for GET $path")
                        throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5300, e)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.log(LogId.E_5300, e)
            throw e
        }
    }

    // ── パーサ ──────────────────────────────────────────────

    private fun parseLootList(json: String): List<LootModel> {
        val array = JsonParser.parseString(json).asJsonArray
        return array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            parseLootObject(element.asJsonObject)
        }
    }

    private fun parseLoot(json: String): LootModel {
        val obj = JsonParser.parseString(json).asJsonObject
        return parseLootObject(obj)
    }

    private fun parseLootObject(obj: JsonObject): LootModel {
        val entriesArray = obj.getAsJsonArray("entries")
        val entries = entriesArray?.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val e = element.asJsonObject
            val category = e.get("category")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            val itemId = e.get("itemId")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            LootEntry(
                category = category,
                itemId = itemId,
                minAmount = e.get("minAmount")?.asInt ?: 1,
                maxAmount = e.get("maxAmount")?.asInt ?: 1,
                weight = e.get("weight")?.asDouble ?: 100.0,
            )
        } ?: emptyList()

        return LootModel(
            schemaVersion = obj.get("schemaVersion")?.asInt ?: 1,
            id = obj.get("id").asString,
            name = obj.get("name")?.asString ?: obj.get("id").asString,
            entries = entries,
        )
    }
}

