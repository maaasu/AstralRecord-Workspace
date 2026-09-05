package io.github.maaasu.astralRecord.feature.skill.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryOperationSnapshotParser
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInventoryMutationResult
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillConsumedMaterial
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillMaterialMutationResult
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillMutationException
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillMutationFailure
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillSigil
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillSigilDetachResult
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil
import java.io.IOException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

class LearnedSkillRepository {
    fun findByAccountId(accountId: UUID): List<LearnedSkillInstance> {
        val path = "/api/account-skills/$accountId"
        ApiRequestUtil.buildClient().use { client ->
            val response = client.send(
                ApiRequestUtil.buildRequestBuilder(path).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            return when (response.statusCode()) {
                200 -> parseList(response.body())
                404 -> emptyList()
                else -> throw IOException("Unexpected status ${response.statusCode()} for GET $path")
            }
        }
    }

    /**
     * APIでスキル変更と素材消費を確定する。事前保存・操作排他を済ませた非メインスレッドから呼ぶ。
     * DBをAPI経由で更新するが、Pluginのキャッシュやインベントリは変更しない。
     * 呼出しごとに新しい冪等IDを採番する。再送が必要な場合はID指定版を使用する。
     * @param accountId 対象所有者account
     * @param skillId 習得するスキルmaster ID
     * @param updatedBy 操作したaccount
     * @return 確定個体と消費・返却情報。snapshotが未収録・不正ならnullとし呼出元で正本GETを行う
     * @throws LearnedSkillMutationException APIが業務失敗またはエラーstatusを返した場合
     * @throws IOException 通信に失敗した場合
     * @throws RuntimeException 必須応答の解析失敗または待機中断の場合
     */
    fun learn(accountId: UUID, skillId: String, updatedBy: UUID): LearnedSkillMaterialMutationResult {
        return learn(accountId, skillId, updatedBy, UUID.randomUUID())
    }

    /**
     * APIでスキル変更と素材消費を確定する。事前保存・操作排他を済ませた非メインスレッドから呼ぶ。
     * DBをAPI経由で更新するが、Pluginのキャッシュやインベントリは変更しない。
     * @param accountId 対象所有者account
     * @param skillId 習得するスキルmaster ID
     * @param updatedBy 操作したaccount
     * @param operationId 再送でも保持する冪等ID
     * @return 確定個体と消費・返却情報。snapshotが未収録・不正ならnullとし呼出元で正本GETを行う
     * @throws LearnedSkillMutationException APIが業務失敗またはエラーstatusを返した場合
     * @throws IOException 通信に失敗した場合
     * @throws RuntimeException 必須応答の解析失敗または待機中断の場合
     */
    fun learn(
        accountId: UUID,
        skillId: String,
        updatedBy: UUID,
        operationId: UUID,
    ): LearnedSkillMaterialMutationResult {
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("skillId", skillId)
            addProperty("operationId", operationId.toString())
            addProperty("updatedBy", updatedBy.toString())
        }
        return mutateWithMaterials("/api/account-skills/$accountId/learn", body)
    }

    /**
     * APIでスキル変更と素材消費を確定する。事前保存・操作排他を済ませた非メインスレッドから呼ぶ。
     * DBをAPI経由で更新するが、Pluginのキャッシュやインベントリは変更しない。
     * 呼出しごとに新しい冪等IDを採番する。再送が必要な場合はID指定版を使用する。
     * @param accountId 対象所有者account
     * @param learnedSkillId 対象習得個体ID
     * @param updatedBy 操作したaccount
     * @return 確定個体と消費・返却情報。snapshotが未収録・不正ならnullとし呼出元で正本GETを行う
     * @throws LearnedSkillMutationException APIが業務失敗またはエラーstatusを返した場合
     * @throws IOException 通信に失敗した場合
     * @throws RuntimeException 必須応答の解析失敗または待機中断の場合
     */
    fun levelUp(accountId: UUID, learnedSkillId: UUID, updatedBy: UUID): LearnedSkillMaterialMutationResult {
        return levelUp(accountId, learnedSkillId, updatedBy, UUID.randomUUID())
    }

    /**
     * APIでスキル変更と素材消費を確定する。事前保存・操作排他を済ませた非メインスレッドから呼ぶ。
     * DBをAPI経由で更新するが、Pluginのキャッシュやインベントリは変更しない。
     * @param accountId 対象所有者account
     * @param learnedSkillId 対象習得個体ID
     * @param updatedBy 操作したaccount
     * @param operationId 再送でも保持する冪等ID
     * @return 確定個体と消費・返却情報。snapshotが未収録・不正ならnullとし呼出元で正本GETを行う
     * @throws LearnedSkillMutationException APIが業務失敗またはエラーstatusを返した場合
     * @throws IOException 通信に失敗した場合
     * @throws RuntimeException 必須応答の解析失敗または待機中断の場合
     */
    fun levelUp(
        accountId: UUID,
        learnedSkillId: UUID,
        updatedBy: UUID,
        operationId: UUID,
    ): LearnedSkillMaterialMutationResult {
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("operationId", operationId.toString())
            addProperty("updatedBy", updatedBy.toString())
        }
        return mutateWithMaterials("/api/account-skills/$accountId/$learnedSkillId/level-up", body)
    }

    /**
     * APIでスキル変更と素材消費を確定する。事前保存・操作排他を済ませた非メインスレッドから呼ぶ。
     * DBをAPI経由で更新するが、Pluginのキャッシュやインベントリは変更しない。
     * 呼出しごとに新しい冪等IDを採番する。再送が必要な場合はID指定版を使用する。
     * @param accountId 対象所有者account
     * @param learnedSkillId 対象習得個体ID
     * @param orbInventoryEntryId 消費オーブentry ID
     * @param sigilId 装着シジルmaster ID
     * @param sigilInventoryEntryId 消費シジルentry ID
     * @param updatedBy 操作したaccount
     * @return 確定個体と消費・返却情報。snapshotが未収録・不正ならnullとし呼出元で正本GETを行う
     * @throws LearnedSkillMutationException APIが業務失敗またはエラーstatusを返した場合
     * @throws IOException 通信に失敗した場合
     * @throws RuntimeException 必須応答の解析失敗または待機中断の場合
     */
    fun attachSigil(
        accountId: UUID,
        learnedSkillId: UUID,
        orbInventoryEntryId: UUID,
        sigilId: String,
        sigilInventoryEntryId: UUID,
        updatedBy: UUID,
    ): LearnedSkillInventoryMutationResult {
        return attachSigil(
            accountId,
            learnedSkillId,
            orbInventoryEntryId,
            sigilId,
            sigilInventoryEntryId,
            updatedBy,
            UUID.randomUUID(),
        )
    }

    /**
     * APIでスキル変更と素材消費を確定する。事前保存・操作排他を済ませた非メインスレッドから呼ぶ。
     * DBをAPI経由で更新するが、Pluginのキャッシュやインベントリは変更しない。
     * @param accountId 対象所有者account
     * @param learnedSkillId 対象習得個体ID
     * @param orbInventoryEntryId 消費オーブentry ID
     * @param sigilId 装着シジルmaster ID
     * @param sigilInventoryEntryId 消費シジルentry ID
     * @param updatedBy 操作したaccount
     * @param operationId 再送でも保持する冪等ID
     * @return 確定個体と消費・返却情報。snapshotが未収録・不正ならnullとし呼出元で正本GETを行う
     * @throws LearnedSkillMutationException APIが業務失敗またはエラーstatusを返した場合
     * @throws IOException 通信に失敗した場合
     * @throws RuntimeException 必須応答の解析失敗または待機中断の場合
     */
    fun attachSigil(
        accountId: UUID,
        learnedSkillId: UUID,
        orbInventoryEntryId: UUID,
        sigilId: String,
        sigilInventoryEntryId: UUID,
        updatedBy: UUID,
        operationId: UUID,
    ): LearnedSkillInventoryMutationResult {
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("orbInventoryEntryId", orbInventoryEntryId.toString())
            addProperty("sigilId", sigilId)
            addProperty("sigilInventoryEntryId", sigilInventoryEntryId.toString())
            addProperty("operationId", operationId.toString())
            addProperty("updatedBy", updatedBy.toString())
        }
        return mutateWithInventorySnapshot("/api/account-skills/$accountId/$learnedSkillId/sigils", body)
    }

    /**
     * APIでスキル変更と素材消費を確定する。事前保存・操作排他を済ませた非メインスレッドから呼ぶ。
     * DBをAPI経由で更新するが、Pluginのキャッシュやインベントリは変更しない。
     * 呼出しごとに新しい冪等IDを採番する。再送が必要な場合はID指定版を使用する。
     * @param accountId 対象所有者account
     * @param learnedSkillId 対象習得個体ID
     * @param orbInventoryEntryId 消費オーブentry ID
     * @param learnedSkillSigilId 取り外す装着個体ID
     * @param updatedBy 操作したaccount
     * @return 確定個体と消費・返却情報。snapshotが未収録・不正ならnullとし呼出元で正本GETを行う
     * @throws LearnedSkillMutationException APIが業務失敗またはエラーstatusを返した場合
     * @throws IOException 通信に失敗した場合
     * @throws RuntimeException 必須応答の解析失敗または待機中断の場合
     */
    fun detachSigil(
        accountId: UUID,
        learnedSkillId: UUID,
        orbInventoryEntryId: UUID,
        learnedSkillSigilId: UUID,
        updatedBy: UUID,
    ): LearnedSkillSigilDetachResult {
        return detachSigil(
            accountId,
            learnedSkillId,
            orbInventoryEntryId,
            learnedSkillSigilId,
            updatedBy,
            UUID.randomUUID(),
        )
    }

    /**
     * APIでスキル変更と素材消費を確定する。事前保存・操作排他を済ませた非メインスレッドから呼ぶ。
     * DBをAPI経由で更新するが、Pluginのキャッシュやインベントリは変更しない。
     * @param accountId 対象所有者account
     * @param learnedSkillId 対象習得個体ID
     * @param orbInventoryEntryId 消費オーブentry ID
     * @param learnedSkillSigilId 取り外す装着個体ID
     * @param updatedBy 操作したaccount
     * @param operationId 再送でも保持する冪等ID
     * @return 確定個体と消費・返却情報。snapshotが未収録・不正ならnullとし呼出元で正本GETを行う
     * @throws LearnedSkillMutationException APIが業務失敗またはエラーstatusを返した場合
     * @throws IOException 通信に失敗した場合
     * @throws RuntimeException 必須応答の解析失敗または待機中断の場合
     */
    fun detachSigil(
        accountId: UUID,
        learnedSkillId: UUID,
        orbInventoryEntryId: UUID,
        learnedSkillSigilId: UUID,
        updatedBy: UUID,
        operationId: UUID,
    ): LearnedSkillSigilDetachResult {
        val path = "/api/account-skills/$accountId/$learnedSkillId/sigils/$learnedSkillSigilId/detach"
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("orbInventoryEntryId", orbInventoryEntryId.toString())
            addProperty("operationId", operationId.toString())
            addProperty("updatedBy", updatedBy.toString())
        }
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() in 200..299) {
                    val result = JsonParser.parseString(response.body()).asJsonObject
                    return LearnedSkillSigilDetachResult(
                        parseSkill(result.getAsJsonObject("skill")),
                        UUID.fromString(result.get("returnedInventoryEntryId").asString),
                        InventoryOperationSnapshotParser.parse(result.get("inventorySnapshot")),
                    )
                }
                val failure = parseFailure(response.body())
                throw LearnedSkillMutationException(
                    failure,
                    "HTTP ${response.statusCode()} for POST $path: $failure",
                    response.statusCode(),
                )
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }
    }

    fun forget(accountId: UUID, learnedSkillId: UUID, updatedBy: UUID): LearnedSkillInstance {
        return forget(accountId, learnedSkillId, updatedBy, UUID.randomUUID())
    }

    fun forget(
        accountId: UUID,
        learnedSkillId: UUID,
        updatedBy: UUID,
        operationId: UUID,
    ): LearnedSkillInstance {
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("operationId", operationId.toString())
            addProperty("updatedBy", updatedBy.toString())
        }
        return mutate("/api/account-skills/$accountId/$learnedSkillId/forget", body)
    }

    private fun mutate(path: String, body: String): LearnedSkillInstance {
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() in 200..299) {
                    return parseSkill(JsonParser.parseString(response.body()).asJsonObject)
                }
                val failure = parseFailure(response.body())
                throw LearnedSkillMutationException(
                    failure,
                    "HTTP ${response.statusCode()} for POST $path: $failure",
                    response.statusCode(),
                )
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }
    }

    private fun mutateWithMaterials(path: String, body: String): LearnedSkillMaterialMutationResult {
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() in 200..299) {
                    val result = JsonParser.parseString(response.body()).asJsonObject
                    return LearnedSkillMaterialMutationResult(
                        skill = parseSkill(result.getAsJsonObject("skill")),
                        consumedMaterials = result.getAsJsonArray("consumedMaterials")
                            ?.filter { it.isJsonObject }
                            ?.map { element ->
                                val material = element.asJsonObject
                                LearnedSkillConsumedMaterial(
                                    inventoryEntryId = UUID.fromString(material.get("inventoryEntryId").asString),
                                    consumedAmount = material.get("consumedAmount").asLong,
                                )
                            }
                            ?: emptyList(),
                        inventorySnapshot = InventoryOperationSnapshotParser.parse(
                            result.get("inventorySnapshot"),
                        ),
                    )
                }
                val failure = parseFailure(response.body())
                throw LearnedSkillMutationException(
                    failure,
                    "HTTP ${response.statusCode()} for POST $path: $failure",
                    response.statusCode(),
                )
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }
    }

    private fun parseList(json: String): List<LearnedSkillInstance> {
        val array: JsonArray = JsonParser.parseString(json).asJsonArray
        return array.filter { it.isJsonObject }.map { parseSkill(it.asJsonObject) }
    }

    private fun mutateWithInventorySnapshot(
        path: String,
        body: String,
    ): LearnedSkillInventoryMutationResult {
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() in 200..299) {
                    val result = JsonParser.parseString(response.body()).asJsonObject
                    return LearnedSkillInventoryMutationResult(
                        skill = parseSkill(result),
                        inventorySnapshot = InventoryOperationSnapshotParser.parse(
                            result.get("inventorySnapshot"),
                        ),
                    )
                }
                val failure = parseFailure(response.body())
                throw LearnedSkillMutationException(
                    failure,
                    "HTTP ${response.statusCode()} for POST $path: $failure",
                    response.statusCode(),
                )
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }
    }

    private fun parseSkill(obj: JsonObject): LearnedSkillInstance = LearnedSkillInstance(
        learnedSkillId = UUID.fromString(obj.get("learnedSkillId").asString),
        accountId = UUID.fromString(obj.get("accountId").asString),
        skillId = obj.get("skillId").asString,
        level = obj.get("level").asInt,
        sigils = obj.getAsJsonArray("sigils")?.filter { it.isJsonObject }?.map { element ->
            val sigil = element.asJsonObject
            LearnedSkillSigil(
                learnedSkillSigilId = UUID.fromString(sigil.get("learnedSkillSigilId").asString),
                sigilId = sigil.get("sigilId").asString,
                equipGroupId = sigil.get("equipGroupId").asString,
                slotIndex = sigil.get("slotIndex").asInt,
            )
        } ?: emptyList(),
        version = obj.get("version")?.asInt ?: 0,
        createdAt = obj.get("createdAt")?.takeIf { !it.isJsonNull }?.asString?.let(::parseDateTime),
        updatedAt = obj.get("updatedAt")?.takeIf { !it.isJsonNull }?.asString?.let(::parseDateTime),
    )

    private fun parseFailure(body: String): LearnedSkillMutationFailure {
        val raw = runCatching {
            JsonParser.parseString(body).asJsonObject.get("failure")?.asString
        }.getOrNull() ?: return LearnedSkillMutationFailure.UNKNOWN
        val normalized = raw.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase(Locale.ROOT)
        return runCatching { LearnedSkillMutationFailure.valueOf(normalized) }
            .getOrDefault(LearnedSkillMutationFailure.UNKNOWN)
    }

    private fun parseDateTime(raw: String): LocalDateTime = try {
        LocalDateTime.parse(raw)
    } catch (_: DateTimeParseException) {
        OffsetDateTime.parse(raw).toLocalDateTime()
    }
}
