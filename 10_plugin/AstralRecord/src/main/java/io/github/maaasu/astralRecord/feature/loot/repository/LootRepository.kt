package io.github.maaasu.astralRecord.feature.loot.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.loot.model.LootContent
import io.github.maaasu.astralRecord.feature.loot.model.LootModel
import io.github.maaasu.astralRecord.feature.loot.model.LootPoolModel
import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil
import java.io.IOException
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.min

/**
 * AstralRecord API からルートテーブルとプールを取得するリポジトリ。
 */
class LootRepository {

    /**
     * 全ルートテーブルを取得し、参照プールを解決した形で返します。
     * GET /api/loot/table
     * GET /api/loot/pool
     */
    fun findAll(): List<LootModel> {
        val tablePath = "/api/loot/table"
        val poolPath = "/api/loot/pool"
        try {
            val client = ApiRequestUtil.buildClient()
            client.use {
                val tableResponse = client.send(
                    ApiRequestUtil.buildRequestBuilder(tablePath).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                )
                val poolResponse = client.send(
                    ApiRequestUtil.buildRequestBuilder(poolPath).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                )

                if (tableResponse.statusCode() != 200) {
                    Logger.log(LogId.E_5300, "HTTP ${tableResponse.statusCode()} for GET $tablePath")
                    throw IOException("Unexpected status ${tableResponse.statusCode()} for GET $tablePath")
                }
                if (poolResponse.statusCode() != 200) {
                    Logger.log(LogId.E_5300, "HTTP ${poolResponse.statusCode()} for GET $poolPath")
                    throw IOException("Unexpected status ${poolResponse.statusCode()} for GET $poolPath")
                }

                val poolMap = parsePoolList(poolResponse.body()).associateBy { normalizeId(it.id) }
                return parseTableList(tableResponse.body()).map { table -> resolveTable(table, poolMap) }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5300, e, e.message ?: "Interrupted while GET $tablePath / $poolPath")
            throw RuntimeException(e)
        }
    }

    /**
     * 指定ルートテーブルを取得し、参照プールを都度解決して返します。
     * GET /api/loot/table/{tableId}
     * GET /api/loot/pool/{poolId}
     */
    fun findById(lootId: String): LootModel? {
        val normalizedLootId = normalizeId(lootId)
        val encodedId = URLEncoder.encode(normalizedLootId, StandardCharsets.UTF_8).replace("+", "%20")
        val path = "/api/loot/table/$encodedId"

        try {
            val client = ApiRequestUtil.buildClient()
            client.use {
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> {
                        val table = parseTable(response.body())
                        val pools = linkedMapOf<String, LootPoolModel>()
                        table.poolRefs.forEach { poolRef ->
                            val pool = findPoolById(client, poolRef)
                            if (pool != null) {
                                pools[normalizeId(pool.id)] = pool
                            }
                        }
                        val loot = resolveTable(table, pools)
                        Logger.log(LogId.D_5300, normalizedLootId)
                        loot
                    }
                    404 -> {
                        Logger.log(LogId.W_5300, normalizedLootId)
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

    private fun findPoolById(
        client: HttpClient,
        poolIdOrRef: String
    ): LootPoolModel? {
        val normalizedPoolId = normalizeId(poolIdOrRef)
        val encodedId = URLEncoder.encode(normalizedPoolId, StandardCharsets.UTF_8).replace("+", "%20")
        val path = "/api/loot/pool/$encodedId"
        val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return when (response.statusCode()) {
            200 -> parsePool(response.body())
            404 -> null
            else -> {
                Logger.log(LogId.E_5300, "HTTP ${response.statusCode()} for GET $path")
                throw IOException("Unexpected status ${response.statusCode()} for GET $path")
            }
        }
    }

    private fun parseTableList(json: String): List<LootTableDto> {
        val array = JsonParser.parseString(json).asJsonArray
        return array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            parseTableObject(element.asJsonObject)
        }
    }

    private fun parseTable(json: String): LootTableDto {
        val obj = JsonParser.parseString(json).asJsonObject
        return parseTableObject(obj)
    }

    private fun parsePoolList(json: String): List<LootPoolModel> {
        val array = JsonParser.parseString(json).asJsonArray
        return array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            parsePoolObject(element.asJsonObject)
        }
    }

    private fun parsePool(json: String): LootPoolModel {
        val obj = JsonParser.parseString(json).asJsonObject
        return parsePoolObject(obj)
    }

    private fun parseTableObject(obj: JsonObject): LootTableDto {
        val pools = parseStringList(obj.getAsJsonArray("pools"))
        return LootTableDto(
            schemaVersion = obj.get("schemaVersion")?.asInt ?: 1,
            id = obj.get("id")?.asString ?: "",
            rolls = parseAmountRangeUpperBound(obj, "rolls"),
            poolRefs = pools,
        )
    }

    private fun parsePoolObject(obj: JsonObject): LootPoolModel {
        val contentsArray = obj.getAsJsonArray("contents") ?: JsonArray()
        val contents = contentsArray.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val content = element.asJsonObject
            val itemId = content.get("itemId")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            val amountText = content.get("amount")?.takeIf { !it.isJsonNull }?.asString ?: "1"
            val (minAmount, maxAmount) = parseAmountRange(amountText)
            LootContent(
                itemId = normalizeId(itemId),
                minAmount = minAmount,
                maxAmount = maxAmount,
                rate = content.get("rate")?.asDouble ?: 100.0,
            )
        }

        return LootPoolModel(
            id = obj.get("id")?.asString ?: "",
            pick = parseAmountRangeUpperBound(obj, "pick").coerceAtLeast(1),
            contents = contents,
        )
    }

    private fun resolveTable(
        table: LootTableDto,
        poolMap: Map<String, LootPoolModel>
    ): LootModel {
        val pools = table.poolRefs.mapNotNull { poolMap[normalizeId(it)] }
        return LootModel(
            schemaVersion = table.schemaVersion,
            id = table.id,
            name = table.id,
            rolls = table.rolls.coerceAtLeast(1),
            pools = pools,
        )
    }

    private fun parseStringList(array: JsonArray?): List<String> {
        if (array == null) {
            return emptyList()
        }

        return array.mapNotNull { element ->
            if (element.isJsonPrimitive) element.asString else null
        }
    }

    private fun parseAmountRangeUpperBound(obj: JsonObject, key: String): Int {
        val element = obj.get(key) ?: return 1
        if (element.isJsonNull) {
            return 1
        }
        val raw = element.asString.trim()
        val (parsedMin, parsedMax) = parseAmountRange(raw)
        return max(parsedMin, parsedMax).coerceAtLeast(1)
    }

    private fun parseAmountRange(raw: String): Pair<Int, Int> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return 1 to 1
        }

        val parts = trimmed.split("~", limit = 2)
        return try {
            val first = parts.first().trim().toInt()
            val second = if (parts.size >= 2) parts[1].trim().toInt() else first
            min(first, second) to max(first, second)
        } catch (_: NumberFormatException) {
            1 to 1
        }
    }

    private fun normalizeId(value: String): String {
        val trimmed = value.trim()
        val prefixIndex = trimmed.indexOf(':')
        return if (prefixIndex >= 0) {
            trimmed.substring(prefixIndex + 1).trim()
        } else {
            trimmed
        }
    }

    private data class LootTableDto(
        val schemaVersion: Int,
        val id: String,
        val rolls: Int,
        val poolRefs: List<String>,
    )
}
