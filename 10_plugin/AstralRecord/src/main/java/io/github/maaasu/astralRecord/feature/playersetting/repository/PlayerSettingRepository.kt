package io.github.maaasu.astralRecord.feature.playersetting.repository

import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingModel
import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil
import java.io.IOException
import java.net.http.HttpResponse
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 初期 GET 専用。変更は共通 player-state snapshot の playerSettings section で保存する。 */
class PlayerSettingRepository {
    private val formatter = DateTimeFormatter.ISO_DATE_TIME

    fun findByUserId(userId: UUID): List<PlayerSettingModel> {
        val path = "/api/player-setting?user_id=$userId"
        try {
            val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
            val response = ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString())
            return when (response.statusCode()) {
                200 -> JsonParser.parseString(response.body()).asJsonArray.map { parseModel(it.asJsonObject) }
                else -> {
                    val message = "HTTP ${response.statusCode()} for GET $path"
                    Logger.log(LogId.E_5310, message)
                    throw IOException(message)
                }
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5310, exception, exception.message ?: exception.javaClass.simpleName)
            throw RuntimeException(exception)
        }
    }

    private fun parseModel(obj: com.google.gson.JsonObject) = PlayerSettingModel(
        UUID.fromString(obj.get("userSettingId").asString), UUID.fromString(obj.get("userId").asString),
        obj.get("settingKey").asString, obj.get("settingValueJson").asString, obj.get("version").asInt,
        LocalDateTime.parse(obj.get("createdAt").asString, formatter),
        LocalDateTime.parse(obj.get("updatedAt").asString, formatter),
        UUID.fromString(obj.get("createdBy").asString), UUID.fromString(obj.get("updatedBy").asString),
        obj.get("isDeleted").asBoolean
    )
}
