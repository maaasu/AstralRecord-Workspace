package io.github.maaasu.astralRecord.feature.inventory.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil
import java.io.IOException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

class InventoryRepository {

    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    fun findByAccountId(accountId: UUID): List<InventoryModel> {
        val path = "/api/inventory?account_id=$accountId"
        return sendGetList(path, ::parseInventoryList)
    }

    fun findById(inventoryId: UUID): InventoryModel? {
        val path = "/api/inventory/$inventoryId"
        return sendGetSingle(path, ::parseInventoryModel)
    }

    fun findEntries(inventoryId: UUID): List<InventoryEntryModel> {
        val path = "/api/inventory/$inventoryId/entries"
        return sendGetList(path, ::parseInventoryEntryList)
    }

    fun findEntryById(inventoryEntryId: UUID): InventoryEntryModel? {
        val path = "/api/inventory/entries/$inventoryEntryId"
        return sendGetSingle(path, ::parseInventoryEntryModel)
    }

    private fun <T> sendGetList(path: String, parser: (String) -> List<T>): List<T> {
        try {
            ApiRequestUtil.sharedClient().let { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> parser(response.body())
                    404 -> emptyList()
                    else -> throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }
    }

    private fun <T> sendGetSingle(path: String, parser: (String) -> T): T? {
        try {
            ApiRequestUtil.sharedClient().let { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> parser(response.body())
                    404 -> null
                    else -> throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }
    }

    private fun parseInventoryModel(json: String): InventoryModel {
        val obj = JsonParser.parseString(json).asJsonObject
        return obj.toInventoryModel()
    }

    private fun parseInventoryList(json: String): List<InventoryModel> {
        val arr: JsonArray = JsonParser.parseString(json).asJsonArray
        return arr.map { it.asJsonObject.toInventoryModel() }
    }

    /**
     * APIのentry JSONを通信・共有状態変更なしでモデルへ変換する。
     * @param json InventoryEntryResponse形式の必須値を含むJSONオブジェクト
     * @return デコードしたentry
     * @throws RuntimeException JSON構造、日時、UUIDまたは必須値が不正な場合
     */
    fun parseInventoryEntryModel(json: String): InventoryEntryModel {
        val obj = JsonParser.parseString(json).asJsonObject
        return obj.toInventoryEntryModel()
    }

    private fun parseInventoryEntryList(json: String): List<InventoryEntryModel> {
        val arr: JsonArray = JsonParser.parseString(json).asJsonArray
        return arr.map { it.asJsonObject.toInventoryEntryModel() }
    }

    private fun parseApiDateTime(value: String): LocalDateTime {
        return try {
            LocalDateTime.parse(value, formatter)
        } catch (_: DateTimeParseException) {
            OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDateTime()
        }
    }

    private fun JsonObject.toInventoryModel() = InventoryModel(
        inventoryId = UUID.fromString(get("inventoryId").asString),
        accountId = UUID.fromString(get("accountId").asString),
        inventoryType = InventoryType.fromCode(get("inventoryType").asString),
        inventoryProfile = get("inventoryProfile").asString,
        slotCapacity = get("slotCapacity")?.takeIf { !it.isJsonNull }?.asInt,
        isEnabled = get("isEnabled").asBoolean,
        metadataJson = get("metadataJson")?.takeIf { !it.isJsonNull }?.asString,
        createdAt = parseApiDateTime(get("createdAt").asString),
        updatedAt = parseApiDateTime(get("updatedAt").asString),
        createdBy = UUID.fromString(get("createdBy").asString),
        updatedBy = UUID.fromString(get("updatedBy").asString),
        isDeleted = get("isDeleted").asBoolean,
    )

    private fun JsonObject.toInventoryEntryModel() = InventoryEntryModel(
        inventoryEntryId = UUID.fromString(get("inventoryEntryId").asString),
        inventoryId = UUID.fromString(get("inventoryId").asString),
        slotIndex = get("slotIndex")?.takeIf { !it.isJsonNull }?.asInt,
        itemCategory = get("itemCategory").asString,
        itemId = get("itemId")?.takeIf { !it.isJsonNull }?.asString,
        instanceType = get("instanceType")?.takeIf { !it.isJsonNull }?.asString,
        instanceId = get("instanceId")?.takeIf { !it.isJsonNull }?.asString?.let(UUID::fromString),
        quantity = get("quantity").asLong,
        metadataJson = get("metadataJson")?.takeIf { !it.isJsonNull }?.asString,
        createdAt = parseApiDateTime(get("createdAt").asString),
        updatedAt = parseApiDateTime(get("updatedAt").asString),
        createdBy = UUID.fromString(get("createdBy").asString),
        updatedBy = UUID.fromString(get("updatedBy").asString),
        isDeleted = get("isDeleted").asBoolean,
    )
}
