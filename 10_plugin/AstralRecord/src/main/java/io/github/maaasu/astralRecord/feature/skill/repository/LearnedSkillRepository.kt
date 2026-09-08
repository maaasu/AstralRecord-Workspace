package io.github.maaasu.astralRecord.feature.skill.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillSigil
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil
import java.io.IOException
import java.net.http.HttpResponse
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/** 習得済みスキルの初期読込を行う。更新はプレイヤー状態スナップショットへ集約する。 */
class LearnedSkillRepository {
    fun findByAccountId(accountId: UUID): List<LearnedSkillInstance> {
        val path = "/api/account-skills/$accountId"
            ApiRequestUtil.sharedClient().let { client ->
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

    private fun parseList(json: String): List<LearnedSkillInstance> {
        val array: JsonArray = JsonParser.parseString(json).asJsonArray
        return array.filter { it.isJsonObject }.map { parseSkill(it.asJsonObject) }
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

    private fun parseDateTime(raw: String): LocalDateTime = try {
        LocalDateTime.parse(raw)
    } catch (_: DateTimeParseException) {
        OffsetDateTime.parse(raw).toLocalDateTime()
    }
}
