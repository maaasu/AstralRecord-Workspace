package io.github.maaasu.astralRecord.feature.player.repository

import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil
import java.io.IOException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * プレイヤーのログイン/ログアウト履歴を AstralRecord API へ送信するリポジトリ。
 */
class PlayerHistoryRepository {

    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /**
     * 履歴を 1 件登録する。
     *
     * @param userUuid 履歴対象ユーザー UUID
     * @param eventType イベント種別（例: PLAYER_LOGIN, PLAYER_LOGOUT）
     * @param message 履歴メッセージ
     */
    fun insertHistory(userUuid: UUID, eventType: String, message: String) {
        val path = "/api/user/history"
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("userUuid", userUuid.toString())
            addProperty("eventTime", LocalDateTime.now().format(formatter))
            addProperty("eventType", eventType)
            addProperty("source", "PLUGIN")
            addProperty("message", message)
            addProperty("payloadJson", null as String?)
        }

        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    throw IOException("Unexpected status ${response.statusCode()} for POST $path")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }
    }
}
