package io.github.maaasu.astralRecord.feature.account.repository

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.account.model.AccountMode
import io.github.maaasu.astralRecord.feature.account.model.AccountModel
import io.github.maaasu.astralRecord.feature.account.model.AccountDeleteResult
import io.github.maaasu.astralRecord.feature.account.model.ClassProgressModel
import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil
import java.io.IOException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * AstralRecord API を通じてアカウントデータへのアクセスを担うリポジトリ。
 * JDBC による直接 DB アクセスの代わりに HTTP リクエストを使用します。
 */
class AccountRepository {

    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    // -------------------------------------------------------
    // SELECT
    // -------------------------------------------------------

    /**
     * プレイヤー UUID に紐付くアカウント一覧を取得します（論理削除除外）。
     * GET /api/account?user_id={userId}
     */
    fun findByUserId(userId: UUID): List<AccountModel> {
        val path = "/api/account?user_id=$userId"
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> {
                        val list = parseAccountList(response.body())
                        list
                    }
                    404 -> {
                        Logger.log(LogId.W_5150, userId)
                        emptyList()
                    }
                    else -> {
                        Logger.log(LogId.E_5150, "HTTP ${response.statusCode()} for GET $path")
                        throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.error(LogId.E_5150, e, e.message ?: e.javaClass.simpleName)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.error(LogId.E_5150, e, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * アカウント UUID でアカウントを取得します（論理削除除外）。
     * GET /api/account/{uuid}
     */
    fun findByUuid(uuid: UUID): AccountModel? {
        val path = "/api/account/$uuid"
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> {
                        Logger.log(LogId.D_5151, uuid)
                        parseAccountModel(response.body())
                    }
                    404 -> {
                        Logger.log(LogId.W_5151, uuid)
                        null
                    }
                    else -> {
                        Logger.log(LogId.E_5151, "HTTP ${response.statusCode()} for GET $path")
                        throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.error(LogId.E_5151, e, e.message ?: e.javaClass.simpleName)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.error(LogId.E_5151, e, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    // -------------------------------------------------------
    // INSERT
    // -------------------------------------------------------

    /**
     * 新規アカウントを登録します。登録されたアカウント情報（サーバー生成 UUID を含む）を返します。
     * POST /api/account
     */
    fun insert(model: AccountModel): AccountModel {
        val path = "/api/account"
        val body = buildAccountJson(model)
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    Logger.log(LogId.E_5152, "HTTP ${response.statusCode()} for POST $path")
                    throw IOException("Unexpected status ${response.statusCode()} for POST $path")
                }
                val created = parseAccountModel(response.body())
                Logger.log(LogId.D_5152, created.uuid)
                return created
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.error(LogId.E_5152, e, e.message ?: e.javaClass.simpleName)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.error(LogId.E_5152, e, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    // -------------------------------------------------------
    // UPDATE
    // -------------------------------------------------------

    /**
     * 指定プレイヤーの選択中アカウントを切り替えます。
     * PUT /api/account/{targetUuid}
     */
    fun switchActiveAccount(userId: UUID, targetUuid: UUID, updatedBy: UUID): AccountModel {
        val path = "/api/account/$targetUuid"
        val body = buildSwitchActiveAccountJson(updatedBy)
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    Logger.log(LogId.E_5153, "HTTP ${response.statusCode()} for PUT $path")
                    throw IOException("Unexpected status ${response.statusCode()} for PUT $path")
                }
                Logger.log(LogId.D_5153, userId, targetUuid)
                return parseAccountModel(response.body())
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.error(LogId.E_5153, e, e.message ?: e.javaClass.simpleName)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.error(LogId.E_5153, e, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * account.mode を更新します。
     * PUT /api/account/{targetUuid}
     */
    fun updateMode(targetUuid: UUID, mode: AccountMode, updatedBy: UUID): AccountModel {
        val path = "/api/account/$targetUuid"
        val body = buildAccountUpdateJson(isActive = null, mode = mode, updatedBy = updatedBy)
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    Logger.log(LogId.E_5154, "HTTP ${response.statusCode()} for PUT $path")
                    throw IOException("Unexpected status ${response.statusCode()} for PUT $path")
                }
                Logger.log(LogId.D_5154, targetUuid, mode.value)
                return parseAccountModel(response.body())
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.error(LogId.E_5154, e, e.message ?: e.javaClass.simpleName)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.error(LogId.E_5154, e, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * account.accountName を更新します。
     * PUT /api/account/{targetUuid}
     */
    fun updateName(targetUuid: UUID, accountName: String, updatedBy: UUID): AccountModel {
        val path = "/api/account/$targetUuid"
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("accountName", accountName)
            addProperty("updatedBy", updatedBy.toString())
        }
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 409) {
                    throw AccountNameConflictException("Account name is already in use: $accountName")
                }
                if (response.statusCode() !in 200..299) {
                    Logger.log(LogId.E_5162, response.statusCode())
                    throw IOException("Unexpected status ${response.statusCode()} for PUT $path")
                }
                Logger.log(LogId.D_5162, targetUuid, accountName)
                return parseAccountModel(response.body())
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.error(LogId.E_5162, e, e.message ?: e.javaClass.simpleName)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.error(LogId.E_5162, e, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * account.level と account.totalExperience を更新します。
     * PUT /api/account/{targetUuid}
     */
    fun updateProgress(targetUuid: UUID, level: Int, totalExperience: Long, updatedBy: UUID): AccountModel {
        val path = "/api/account/$targetUuid"
        val body = buildAccountUpdateJson(
            isActive = null,
            mode = null,
            updatedBy = updatedBy,
            level = level,
            totalExperience = totalExperience
        )
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    Logger.log(LogId.E_5155, "HTTP ${response.statusCode()} for PUT $path")
                    throw IOException("Unexpected status ${response.statusCode()} for PUT $path")
                }
                Logger.log(LogId.D_5155, targetUuid, level, totalExperience)
                return parseAccountModel(response.body())
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.error(LogId.E_5155, e, e.message ?: e.javaClass.simpleName)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.error(LogId.E_5155, e, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * account.classId / classLevel / classExperience を更新します。
     * PUT /api/account/{targetUuid}
     */
    fun updateClassProgress(
        targetUuid: UUID,
        classId: String,
        classLevel: Int,
        classExperience: Long,
        classProgresses: List<ClassProgressModel>,
        updatedBy: UUID
    ): AccountModel {
        val path = "/api/account/$targetUuid"
        val body = buildAccountUpdateJson(
            isActive = null,
            mode = null,
            updatedBy = updatedBy,
            classId = classId,
            classLevel = classLevel,
            classExperience = classExperience,
            classProgresses = classProgresses
        )
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    Logger.log(LogId.E_5155, "HTTP ${response.statusCode()} for PUT $path")
                    throw IOException("Unexpected status ${response.statusCode()} for PUT $path")
                }
                return parseAccountModel(response.body())
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.error(LogId.E_5155, e, e.message ?: e.javaClass.simpleName)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.error(LogId.E_5155, e, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * アカウントとアカウント専用データを論理削除します。
     * DELETE /api/account/{targetUuid}
     */
    fun delete(targetUuid: UUID, deletedBy: UUID): AccountDeleteResult? {
        val path = "/api/account/$targetUuid"
        val body = ApiRequestUtil.buildJsonBody { addProperty("deletedBy", deletedBy.toString()) }
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> parseAccountDeleteResult(response.body())
                    404 -> null
                    else -> throw IOException("Unexpected status ${response.statusCode()} for DELETE $path")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }
    }

    // -------------------------------------------------------
    // JSON マッピング
    // -------------------------------------------------------

    private fun buildAccountJson(model: AccountModel): String {
        val obj = JsonObject()
        obj.addProperty("userId", model.userId.toString())
        obj.addProperty("accountName", model.accountName)
        obj.addProperty("slotIndex", model.slotIndex)
        obj.addProperty("mode", model.mode.value.toInt())
        obj.addProperty("createdBy", model.createdBy.toString())
        return obj.toString()
    }

    private fun buildSwitchActiveAccountJson(updatedBy: UUID): String {
        return buildAccountUpdateJson(isActive = true, mode = null, updatedBy = updatedBy)
    }

    private fun buildAccountUpdateJson(
        isActive: Boolean?,
        mode: AccountMode?,
        updatedBy: UUID,
        level: Int? = null,
        totalExperience: Long? = null,
        classId: String? = null,
        classLevel: Int? = null,
        classExperience: Long? = null,
        classProgresses: List<ClassProgressModel>? = null
    ): String {
        return ApiRequestUtil.buildJsonBody {
            addProperty("accountName", null as String?)
            if (isActive != null) {
                addProperty("isActive", isActive)
            } else {
                addProperty("isActive", null as Boolean?)
            }
            if (mode != null) {
                addProperty("mode", mode.value.toInt())
            } else {
                addProperty("mode", null as Number?)
            }
            addProperty("menuShortcutsJson", null as String?)
            if (level != null) {
                addProperty("level", level)
            } else {
                addProperty("level", null as Number?)
            }
            if (totalExperience != null) {
                addProperty("totalExperience", totalExperience)
            } else {
                addProperty("totalExperience", null as Number?)
            }
            if (classId != null) {
                addProperty("classId", classId)
            } else {
                addProperty("classId", null as String?)
            }
            if (classLevel != null) {
                addProperty("classLevel", classLevel)
            } else {
                addProperty("classLevel", null as Number?)
            }
            if (classExperience != null) {
                addProperty("classExperience", classExperience)
            } else {
                addProperty("classExperience", null as Number?)
            }
            if (classProgresses != null) {
                add("classProgresses", JsonArray().apply {
                    classProgresses.forEach { progress ->
                        add(JsonObject().apply {
                            addProperty("classId", progress.classId)
                            addProperty("level", progress.level)
                            addProperty("experience", progress.experience)
                        })
                    }
                })
            } else {
                add("classProgresses", JsonNull.INSTANCE)
            }
            addProperty("updatedBy", updatedBy.toString())
        }
    }

    private fun parseAccountModel(json: String): AccountModel {
        val obj = JsonParser.parseString(json).asJsonObject
        return obj.toAccountModel()
    }

    private fun parseAccountList(json: String): List<AccountModel> {
        val arr: JsonArray = JsonParser.parseString(json).asJsonArray
        return arr.map { it.asJsonObject.toAccountModel() }
    }

    private fun parseAccountDeleteResult(json: String): AccountDeleteResult {
        val obj = JsonParser.parseString(json).asJsonObject
        return AccountDeleteResult(
            deletedAccountId = UUID.fromString(obj.get("deletedAccountId").asString),
            userId = UUID.fromString(obj.get("userId").asString),
            deletedSlotIndex = obj.get("deletedSlotIndex").asInt,
            selectedAccountId = UUID.fromString(obj.get("selectedAccountId").asString),
            createdReplacement = obj.get("createdReplacement").asBoolean,
        )
    }

    private fun parseApiDateTime(value: String): LocalDateTime {
        return try {
            LocalDateTime.parse(value, formatter)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDateTime()
            } catch (e: DateTimeParseException) {
                throw DateTimeParseException("Unsupported datetime format: $value", value, e.errorIndex, e)
            }
        }
    }

    private fun JsonObject.toAccountModel() = AccountModel(
        uuid        = UUID.fromString(get("uuid").asString),
        userId      = UUID.fromString(get("userId").asString),
        accountName = get("accountName").asString,
        slotIndex   = get("slotIndex").asInt,
        isActive    = get("isActive").asBoolean,
        mode        = AccountMode.fromValue(get("mode").asByte),
        menuShortcutsJson = get("menuShortcutsJson")?.takeIf { !it.isJsonNull }?.asString ?: "",
        createdAt   = parseApiDateTime(get("createdAt").asString),
        updatedAt   = parseApiDateTime(get("updatedAt").asString),
        createdBy   = UUID.fromString(get("createdBy").asString),
        updatedBy   = UUID.fromString(get("updatedBy").asString),
        isDeleted   = get("isDeleted").asBoolean,
        level        = get("level")?.takeIf { !it.isJsonNull }?.asInt ?: 1,
        totalExperience = get("totalExperience")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
        classId      = get("classId")?.takeIf { !it.isJsonNull }?.asString ?: "adventurer",
        classLevel   = get("classLevel")?.takeIf { !it.isJsonNull }?.asInt ?: 1,
        classExperience = get("classExperience")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
        classProgresses = getAsJsonArray("classProgresses")
            ?.map { element ->
                val progress = element.asJsonObject
                ClassProgressModel(
                    classId = progress.get("classId").asString,
                    level = progress.get("level")?.takeIf { !it.isJsonNull }?.asInt ?: 1,
                    experience = progress.get("experience")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                )
            }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(
                ClassProgressModel(
                    classId = get("classId")?.takeIf { !it.isJsonNull }?.asString ?: "adventurer",
                    level = get("classLevel")?.takeIf { !it.isJsonNull }?.asInt ?: 1,
                    experience = get("classExperience")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                )
            ),
    )
}
