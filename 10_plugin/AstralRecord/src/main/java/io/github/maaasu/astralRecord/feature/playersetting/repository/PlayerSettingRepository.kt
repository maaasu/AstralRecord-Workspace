package io.github.maaasu.astralRecord.feature.playersetting.repository

import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.playersetting.OptimisticLockConflictException
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingModel
import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil
import java.io.IOException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * AstralRecord API の /api/player-setting と通信する repository です。
 */
class PlayerSettingRepository {

    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_DATE_TIME

    fun findByUserId(userId: UUID): List<PlayerSettingModel> {
        val path = "/api/player-setting?user_id=$userId"
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> JsonParser.parseString(response.body()).asJsonArray.map { parseModel(it.asJsonObject) }
                    else -> {
                        val message = "HTTP ${response.statusCode()} for GET $path"
                        Logger.log(LogId.E_5310, message)
                        throw IOException(message)
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5310, e, e.message ?: "Interrupted while GET $path")
            throw RuntimeException(e)
        }
    }

    fun create(
        userId: UUID,
        settingKey: String,
        settingValueJson: String,
        createdBy: UUID
    ): PlayerSettingModel {
        val path = "/api/player-setting"
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("userId", userId.toString())
            addProperty("settingKey", settingKey)
            addProperty("settingValueJson", settingValueJson)
            addProperty("createdBy", createdBy.toString())
        }
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200, 201 -> parseModel(JsonParser.parseString(response.body()).asJsonObject)
                    else -> {
                        val message = "HTTP ${response.statusCode()} for POST $path"
                        Logger.log(LogId.E_5311, message)
                        throw IOException(message)
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5311, e, e.message ?: "Interrupted while POST $path")
            throw RuntimeException(e)
        }
    }

    fun update(
        userSettingId: UUID,
        settingValueJson: String,
        expectedVersion: Int,
        updatedBy: UUID
    ): PlayerSettingModel? {
        val path = "/api/player-setting/$userSettingId"
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("settingValueJson", settingValueJson)
            addProperty("expectedVersion", expectedVersion)
            addProperty("updatedBy", updatedBy.toString())
        }
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> parseModel(JsonParser.parseString(response.body()).asJsonObject)
                    404 -> null
                    409 -> throw OptimisticLockConflictException(parseModel(JsonParser.parseString(response.body()).asJsonObject))
                    else -> {
                        val message = "HTTP ${response.statusCode()} for PUT $path"
                        Logger.log(LogId.E_5312, message)
                        throw IOException(message)
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5312, e, e.message ?: "Interrupted while PUT $path")
            throw RuntimeException(e)
        }
    }

    private fun parseModel(obj: com.google.gson.JsonObject): PlayerSettingModel {
        return PlayerSettingModel(
            UUID.fromString(obj.get("userSettingId").asString),
            UUID.fromString(obj.get("userId").asString),
            obj.get("settingKey").asString,
            obj.get("settingValueJson").asString,
            obj.get("version").asInt,
            LocalDateTime.parse(obj.get("createdAt").asString, formatter),
            LocalDateTime.parse(obj.get("updatedAt").asString, formatter),
            UUID.fromString(obj.get("createdBy").asString),
            UUID.fromString(obj.get("updatedBy").asString),
            obj.get("isDeleted").asBoolean
        )
    }
}
