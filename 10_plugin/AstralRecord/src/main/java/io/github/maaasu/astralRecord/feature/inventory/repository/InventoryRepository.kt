package io.github.maaasu.astralRecord.feature.inventory.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryDraft
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryProfile
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

    fun create(
        accountId: UUID,
        inventoryType: InventoryType,
        slotCapacity: Int?,
        createdBy: UUID,
        inventoryProfile: InventoryProfile = InventoryProfile.GAME,
        metadataJson: String? = null,
    ): InventoryModel {
        val path = "/api/inventory"
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("accountId", accountId.toString())
            addProperty("inventoryType", inventoryType.code)
            addProperty("inventoryProfile", inventoryProfile.code)
            if (slotCapacity != null) {
                addProperty("slotCapacity", slotCapacity)
            } else {
                addProperty("slotCapacity", null as Number?)
            }
            addProperty("isEnabled", true)
            if (metadataJson != null) {
                addProperty("metadataJson", metadataJson)
            } else {
                addProperty("metadataJson", null as String?)
            }
            addProperty("createdBy", createdBy.toString())
        }

        return sendWithBody(path, "POST", body, ::parseInventoryModel)
    }

    fun findEntries(inventoryId: UUID): List<InventoryEntryModel> {
        val path = "/api/inventory/$inventoryId/entries"
        return sendGetList(path, ::parseInventoryEntryList)
    }

    fun findEntryById(inventoryEntryId: UUID): InventoryEntryModel? {
        val path = "/api/inventory/entries/$inventoryEntryId"
        return sendGetSingle(path, ::parseInventoryEntryModel)
    }

    fun createEntry(
        inventoryId: UUID,
        draft: InventoryEntryDraft,
        createdBy: UUID,
    ): InventoryEntryModel {
        val path = "/api/inventory/$inventoryId/entries"
        val body = buildEntryJson(draft, createdBy, updatedBy = null)
        return sendWithBody(path, "POST", body, ::parseInventoryEntryModel)
    }

    fun updateEntry(
        inventoryEntryId: UUID,
        draft: InventoryEntryDraft,
        updatedBy: UUID,
    ): InventoryEntryModel {
        val path = "/api/inventory/entries/$inventoryEntryId"
        val body = buildEntryJson(draft, createdBy = null, updatedBy = updatedBy)
        return sendWithBody(path, "PUT", body, ::parseInventoryEntryModel)
    }

    fun replaceEntries(
        inventoryId: UUID,
        drafts: List<InventoryEntryDraft>,
        updatedBy: UUID,
    ): List<InventoryEntryModel> {
        val path = "/api/inventory/$inventoryId/entries"
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("updatedBy", updatedBy.toString())
            val entries = JsonArray()
            drafts.forEach { draft ->
                val entry = JsonObject()
                if (draft.slotIndex != null) {
                    entry.addProperty("slotIndex", draft.slotIndex)
                } else {
                    entry.addProperty("slotIndex", null as Number?)
                }
                entry.addProperty("itemCategory", draft.itemCategory)
                if (draft.itemId != null) {
                    entry.addProperty("itemId", draft.itemId)
                } else {
                    entry.addProperty("itemId", null as String?)
                }
                if (draft.instanceType != null) {
                    entry.addProperty("instanceType", draft.instanceType)
                } else {
                    entry.addProperty("instanceType", null as String?)
                }
                if (draft.instanceId != null) {
                    entry.addProperty("instanceId", draft.instanceId.toString())
                } else {
                    entry.addProperty("instanceId", null as String?)
                }
                entry.addProperty("quantity", draft.quantity)
                if (draft.metadataJson != null) {
                    entry.addProperty("metadataJson", draft.metadataJson)
                } else {
                    entry.addProperty("metadataJson", null as String?)
                }
                entries.add(entry)
            }
            add("entries", entries)
        }
        return sendWithBody(path, "PUT", body, ::parseInventoryEntryList)
    }

    fun deleteEntry(
        inventoryEntryId: UUID,
        updatedBy: UUID,
    ): Boolean {
        val path = "/api/inventory/entries/$inventoryEntryId?updated_by=$updatedBy"
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).DELETE().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    204 -> true
                    404 -> false
                    else -> throw IOException("Unexpected status ${response.statusCode()} for DELETE $path")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }
    }

    fun updateMetadata(
        inventoryId: UUID,
        metadataJson: String?,
        updatedBy: UUID,
    ): InventoryModel {
        val path = "/api/inventory/$inventoryId"
        val body = ApiRequestUtil.buildJsonBody {
            if (metadataJson != null) {
                addProperty("metadataJson", metadataJson)
            } else {
                addProperty("metadataJson", null as String?)
            }
            addProperty("updatedBy", updatedBy.toString())
        }
        return sendWithBody(path, "PUT", body, ::parseInventoryModel)
    }

    private fun buildEntryJson(
        draft: InventoryEntryDraft,
        createdBy: UUID?,
        updatedBy: UUID?,
    ): String {
        return ApiRequestUtil.buildJsonBody {
            if (draft.slotIndex != null) {
                addProperty("slotIndex", draft.slotIndex)
            } else {
                addProperty("slotIndex", null as Number?)
            }
            addProperty("itemCategory", draft.itemCategory)
            if (draft.itemId != null) {
                addProperty("itemId", draft.itemId)
            } else {
                addProperty("itemId", null as String?)
            }
            if (draft.instanceType != null) {
                addProperty("instanceType", draft.instanceType)
            } else {
                addProperty("instanceType", null as String?)
            }
            if (draft.instanceId != null) {
                addProperty("instanceId", draft.instanceId.toString())
            } else {
                addProperty("instanceId", null as String?)
            }
            addProperty("quantity", draft.quantity)
            if (draft.metadataJson != null) {
                addProperty("metadataJson", draft.metadataJson)
            } else {
                addProperty("metadataJson", null as String?)
            }
            if (createdBy != null) {
                addProperty("createdBy", createdBy.toString())
            }
            if (updatedBy != null) {
                addProperty("updatedBy", updatedBy.toString())
            }
        }
    }

    private fun <T> sendGetList(path: String, parser: (String) -> List<T>): List<T> {
        try {
            ApiRequestUtil.buildClient().use { client ->
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
            ApiRequestUtil.buildClient().use { client ->
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

    private fun <T> sendWithBody(path: String, method: String, body: String, parser: (String) -> T): T {
        try {
            ApiRequestUtil.buildClient().use { client ->
                val builder = ApiRequestUtil.buildRequestBuilder(path)
                val request = when (method) {
                    "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
                    "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build()
                    else -> throw IllegalArgumentException("Unsupported method: $method")
                }
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    throw IOException("Unexpected status ${response.statusCode()} for $method $path")
                }
                return parser(response.body())
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

    private fun parseInventoryEntryModel(json: String): InventoryEntryModel {
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
