package io.github.maaasu.astralRecord.feature.inventory.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentLoadoutModel
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentLoadoutSlotModel
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryProfile
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil
import java.io.IOException
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

class EquipmentLoadoutRepository {

    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    fun findByAccountId(
        accountId: UUID,
        loadoutProfile: InventoryProfile = InventoryProfile.GAME,
    ): List<EquipmentLoadoutModel> {
        val path = "/api/equipment/loadouts?account_id=$accountId&loadout_profile=${loadoutProfile.code}"
        return sendGetList(path, ::parseLoadoutList)
    }

    fun findById(loadoutId: UUID): EquipmentLoadoutModel? {
        val path = "/api/equipment/loadouts/$loadoutId"
        return sendGetSingle(path, ::parseLoadoutModel)
    }

    fun create(
        accountId: UUID,
        loadoutName: String,
        createdBy: UUID,
        loadoutProfile: InventoryProfile = InventoryProfile.GAME,
        sortOrder: Int = 0,
        isActive: Boolean = false,
        metadataJson: String? = null,
    ): EquipmentLoadoutModel {
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("accountId", accountId.toString())
            addProperty("loadoutProfile", loadoutProfile.code)
            addProperty("loadoutName", loadoutName)
            addProperty("sortOrder", sortOrder)
            addProperty("isActive", isActive)
            if (metadataJson != null) {
                addProperty("metadataJson", metadataJson)
            } else {
                addProperty("metadataJson", null as String?)
            }
            addProperty("createdBy", createdBy.toString())
        }
        return sendWithBody("/api/equipment/loadouts", "POST", body, ::parseLoadoutModel)
    }

    fun activate(loadoutId: UUID, updatedBy: UUID): EquipmentLoadoutModel? {
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("updatedBy", updatedBy.toString())
        }
        return sendWithNullableBody("/api/equipment/loadouts/$loadoutId/activate", "POST", body, ::parseLoadoutModel)
    }

    fun upsertSlot(
        loadoutId: UUID,
        slotType: String,
        slotIndex: Int,
        equipmentInstanceId: UUID,
        updatedBy: UUID,
    ): EquipmentLoadoutSlotModel? {
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("slotType", slotType)
            addProperty("slotIndex", slotIndex)
            addProperty("equipmentInstanceId", equipmentInstanceId.toString())
            addProperty("updatedBy", updatedBy.toString())
        }
        return sendWithNullableBody("/api/equipment/loadouts/$loadoutId/slots", "PUT", body, ::parseSlotModel)
    }

    fun deleteSlot(
        loadoutId: UUID,
        slotType: String,
        slotIndex: Int,
        updatedBy: UUID,
    ): Boolean {
        val encodedSlotType = URLEncoder.encode(slotType, StandardCharsets.UTF_8)
        val path = "/api/equipment/loadouts/$loadoutId/slots/$encodedSlotType/$slotIndex?updated_by=$updatedBy"
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

    private fun <T> sendGetList(path: String, parser: (String) -> List<T>): List<T> {
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> parser(response.body())
                    404 -> emptyList()
                    500 -> emptyList()
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
        return sendWithNullableBody(path, method, body, parser)
            ?: throw IOException("Unexpected 404 for $method $path")
    }

    private fun <T> sendWithNullableBody(path: String, method: String, body: String, parser: (String) -> T): T? {
        try {
            ApiRequestUtil.buildClient().use { client ->
                val builder = ApiRequestUtil.buildRequestBuilder(path)
                val request = when (method) {
                    "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
                    "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build()
                    else -> throw IllegalArgumentException("Unsupported method: $method")
                }
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    in 200..299 -> parser(response.body())
                    404 -> null
                    else -> throw IOException("Unexpected status ${response.statusCode()} for $method $path")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }
    }

    private fun parseLoadoutModel(json: String): EquipmentLoadoutModel {
        val obj = JsonParser.parseString(json).asJsonObject
        return obj.toLoadoutModel()
    }

    private fun parseLoadoutList(json: String): List<EquipmentLoadoutModel> {
        val arr: JsonArray = JsonParser.parseString(json).asJsonArray
        return arr.map { it.asJsonObject.toLoadoutModel() }
    }

    private fun parseSlotModel(json: String): EquipmentLoadoutSlotModel {
        val obj = JsonParser.parseString(json).asJsonObject
        return obj.toSlotModel()
    }

    private fun parseApiDateTime(value: String): LocalDateTime {
        return try {
            LocalDateTime.parse(value, formatter)
        } catch (_: DateTimeParseException) {
            OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDateTime()
        }
    }

    private fun JsonObject.toLoadoutModel() = EquipmentLoadoutModel(
        equipmentLoadoutId = UUID.fromString(get("equipmentLoadoutId").asString),
        accountId = UUID.fromString(get("accountId").asString),
        loadoutProfile = get("loadoutProfile").asString,
        loadoutName = get("loadoutName").asString,
        sortOrder = get("sortOrder").asInt,
        isActive = get("isActive").asBoolean,
        metadataJson = get("metadataJson")?.takeIf { !it.isJsonNull }?.asString,
        slots = get("slots")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asJsonObject.toSlotModel() } ?: emptyList(),
        createdAt = parseApiDateTime(get("createdAt").asString),
        updatedAt = parseApiDateTime(get("updatedAt").asString),
        createdBy = UUID.fromString(get("createdBy").asString),
        updatedBy = UUID.fromString(get("updatedBy").asString),
        isDeleted = get("isDeleted").asBoolean,
    )

    private fun JsonObject.toSlotModel() = EquipmentLoadoutSlotModel(
        equipmentLoadoutSlotId = UUID.fromString(get("equipmentLoadoutSlotId").asString),
        equipmentLoadoutId = UUID.fromString(get("equipmentLoadoutId").asString),
        slotType = get("slotType").asString,
        slotIndex = get("slotIndex").asInt,
        equipmentInstanceId = UUID.fromString(get("equipmentInstanceId").asString),
        createdAt = parseApiDateTime(get("createdAt").asString),
        updatedAt = parseApiDateTime(get("updatedAt").asString),
        createdBy = UUID.fromString(get("createdBy").asString),
        updatedBy = UUID.fromString(get("updatedBy").asString),
        isDeleted = get("isDeleted").asBoolean,
    )
}
