package io.github.maaasu.astralRecord.feature.user.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.user.model.UserModel
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
 * AstralRecord API を通じてユーザーデータへのアクセスを担うリポジトリ。
 * JDBC による直接 DB アクセスの代わりに HTTP リクエストを使用します。
 */
class UserRepository {

    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME


    // -------------------------------------------------------
    // SELECT
    // -------------------------------------------------------

    /**
     * UUID でユーザーを取得します（論理削除除外）。
     * GET /api/user/{uuid}
     */
    fun findByUuid(uuid: UUID): UserModel? {
        val path = "/api/user/$uuid"
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> {
                        Logger.log(LogId.D_5055, uuid)
                        parseUserModel(response.body())
                    }
                    404 -> {
                        Logger.log(LogId.W_5055, uuid)
                        null
                    }
                    else -> {
                        val message = "HTTP ${response.statusCode()} for GET $path"
                        Logger.log(LogId.E_5055, message)
                        throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5055, e, e.message ?: "Interrupted while GET $path")
            throw RuntimeException(e)
        }
    }

    /**
     * UUID でユーザーの存在を確認します。初参加チェックなど 404 が正常系となる用途で使用します。
     * 404 の場合はログを出力しません。
     * GET /api/user/{uuid}
     *
     * @return ユーザーが存在する場合は [UserModel]、存在しない場合は null
     */
    fun findByUuidSilent(uuid: UUID): UserModel? {
        val path = "/api/user/$uuid"
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> {
                        Logger.log(LogId.D_5055, uuid)
                        parseUserModel(response.body())
                    }
                    404 -> null
                    else -> {
                        val message = "HTTP ${response.statusCode()} for GET $path"
                        Logger.log(LogId.E_5055, message)
                        throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5055, e, e.message ?: "Interrupted while GET $path")
            throw RuntimeException(e)
        }
    }

    /**
     * Minecraft ID でユーザーを取得します（論理削除除外）。
     * GET /api/user/mcid/{mcid}
     */
    fun findByMcid(mcid: String): UserModel? {
        val path = "/api/user/mcid/$mcid"
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> parseUserModel(response.body())
                    404 -> null
                    else -> throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }
    }

    // -------------------------------------------------------
    // INSERT
    // -------------------------------------------------------

    /**
     * 新規ユーザーを登録します。
     * POST /api/user
     */
    fun insert(model: UserModel) {
        val path = "/api/user"
        val body = buildUserJson(model)
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    val message = "HTTP ${response.statusCode()} for POST $path"
                    Logger.log(LogId.E_5056, message)
                    throw IOException("Unexpected status ${response.statusCode()} for POST $path")
                }
                Logger.log(LogId.D_5056, model.uuid)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5056, e, e.message ?: "Interrupted while POST $path")
            throw RuntimeException(e)
        }
    }

    // -------------------------------------------------------
    // UPDATE
    // -------------------------------------------------------

    /**
     * 選択中アカウント UUID のみを更新します。
     * 新規ユーザー登録フローの STEP3（account 作成後の account_id 紐付け）で使用します。
     * PUT /api/user/{uuid}
     */
    fun updateAccountId(uuid: UUID, accountId: UUID, updatedBy: UUID) {
        val path = "/api/user/$uuid"
        val body = buildUserUpdateJson(
            lastJoinDate = null,
            globalIp = null,
            accountId = accountId,
            updatedBy = updatedBy,
        )
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    val message = "HTTP ${response.statusCode()} for PUT $path"
                    Logger.log(LogId.E_5057, message)
                    throw IOException("Unexpected status ${response.statusCode()} for PUT $path")
                }
                Logger.log(LogId.D_5057, uuid, accountId)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5057, e, e.message ?: "Interrupted while PUT $path")
            throw RuntimeException(e)
        }
    }

    /**
     * 最終参加日時・グローバル IP・選択アカウントを更新します。
     * PUT /api/user/{uuid}
     */
    fun updateJoinInfo(uuid: UUID, ip: String, accountId: UUID, updatedBy: UUID) {
        val path = "/api/user/$uuid"
        val lastJoinDate = LocalDateTime.now().format(formatter)
        val body = buildUserUpdateJson(
            lastJoinDate = lastJoinDate,
            globalIp = ip,
            accountId = accountId,
            updatedBy = updatedBy,
        )
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    val message = "HTTP ${response.statusCode()} for PUT $path"
                    Logger.log(LogId.E_5058, message)
                    throw IOException("Unexpected status ${response.statusCode()} for PUT $path")
                }
                Logger.log(LogId.D_5058, uuid)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5058, e, e.message ?: "Interrupted while PUT $path")
            throw RuntimeException(e)
        }
    }

    /**
     * user.permission を更新します。
     * PUT /api/user/{uuid}
     */
    fun updatePermission(uuid: UUID, permission: Int, updatedBy: UUID) {
        val path = "/api/user/$uuid"
        val body = buildUserUpdateJson(
            lastJoinDate = null,
            globalIp = null,
            accountId = null,
            permission = permission,
            updatedBy = updatedBy,
        )
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    val message = "HTTP ${response.statusCode()} for PUT $path"
                    Logger.log(LogId.E_5059, message)
                    throw IOException("Unexpected status ${response.statusCode()} for PUT $path")
                }
                Logger.log(LogId.D_5059, uuid, permission)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5059, e, e.message ?: "Interrupted while PUT $path")
            throw RuntimeException(e)
        }
    }

    /**
     * ユーザー履歴を登録します。
     * POST /api/user/history
     *
     * @param userUuid 対象ユーザー UUID
     * @param eventType 履歴種別（例: PLAYER_LOGIN）
     * @param source 発生元（例: PLUGIN）
     * @param message 履歴メッセージ
     */
    fun insertHistory(userUuid: UUID, eventType: String, source: String, message: String) {
        val path = "/api/user/history"
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("userUuid", userUuid.toString())
            addProperty("eventTime", LocalDateTime.now().format(formatter))
            addProperty("eventType", eventType)
            addProperty("source", source)
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
                    val message2 = "HTTP ${response.statusCode()} for POST $path"
                    Logger.log(LogId.E_5060, message2)
                    throw IOException("Unexpected status ${response.statusCode()} for POST $path")
                }
                Logger.log(LogId.D_5060, userUuid, eventType)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5060, e, e.message ?: "Interrupted while POST $path")
            throw RuntimeException(e)
        }
    }

    // -------------------------------------------------------
    // JSON マッピング
    // -------------------------------------------------------

    private fun buildUserJson(model: UserModel): String {
        val obj = JsonObject()
        obj.addProperty("uuid", model.uuid.toString())
        obj.addProperty("mcid", model.mcid)
        obj.addProperty("joinDate", model.joinDate.format(formatter))
        obj.addProperty("lastJoinDate", model.lastJoinDate.format(formatter))
        obj.addProperty("globalIp", model.globalIp)
        obj.addProperty("createdBy", model.createdBy.toString())
        return obj.toString()
    }

    private fun buildUserUpdateJson(
        lastJoinDate: String?,
        globalIp: String?,
        accountId: UUID?,
        permission: Int? = null,
        updatedBy: UUID,
    ): String {
        return ApiRequestUtil.buildJsonBody {
            addProperty("mcid", null as String?)
            addProperty("lastJoinDate", lastJoinDate)
            addProperty("globalIp", globalIp)
            addProperty("accountId", accountId?.toString())
            addProperty("banIndefinite", null as Boolean?)
            addProperty("banDate", null as String?)
            addProperty("kickIp", null as Boolean?)
            if (permission != null) {
                addProperty("permission", permission)
            } else {
                addProperty("permission", null as Number?)
            }
            addProperty("updatedBy", updatedBy.toString())
        }
    }

    private fun parseUserModel(json: String): UserModel {
        val obj = JsonParser.parseString(json).asJsonObject
        return UserModel(
            uuid          = UUID.fromString(obj.get("uuid").asString),
            mcid          = obj.get("mcid").asString,
            joinDate      = LocalDateTime.parse(obj.get("joinDate").asString, formatter),
            lastJoinDate  = LocalDateTime.parse(obj.get("lastJoinDate").asString, formatter),
            globalIp      = obj.get("globalIp").asString,
            accountId     = obj.get("accountId")?.takeIf { !it.isJsonNull }?.asString
                                ?.let { UUID.fromString(it) },
            banIndefinite = obj.get("banIndefinite").asBoolean,
            banDate       = obj.get("banDate")?.takeIf { !it.isJsonNull }?.asString
                                ?.let { LocalDateTime.parse(it, formatter) },
            kickIp        = obj.get("kickIp").asBoolean,
            permission    = obj.get("permission").asInt,
            createdAt     = LocalDateTime.parse(obj.get("createdAt").asString, formatter),
            updatedAt     = LocalDateTime.parse(obj.get("updatedAt").asString, formatter),
            createdBy     = UUID.fromString(obj.get("createdBy").asString),
            updatedBy     = UUID.fromString(obj.get("updatedBy").asString),
            isDeleted     = obj.get("isDeleted").asBoolean,
        )
    }
}
