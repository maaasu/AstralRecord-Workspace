package io.github.maaasu.astralRecord.feature.skill.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillMutationException
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillMutationFailure
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillSigil
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

    fun learn(accountId: UUID, skillId: String, gemInventoryEntryId: UUID, updatedBy: UUID): LearnedSkillInstance {
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("skillId", skillId)
            addProperty("gemInventoryEntryId", gemInventoryEntryId.toString())
            addProperty("updatedBy", updatedBy.toString())
        }
        return mutate("/api/account-skills/$accountId/learn", body)
    }

    fun levelUp(accountId: UUID, learnedSkillId: UUID, gemInventoryEntryId: UUID, updatedBy: UUID): LearnedSkillInstance {
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("gemInventoryEntryId", gemInventoryEntryId.toString())
            addProperty("updatedBy", updatedBy.toString())
        }
        return mutate("/api/account-skills/$accountId/$learnedSkillId/level-up", body)
    }

    fun attachSigil(
        accountId: UUID,
        learnedSkillId: UUID,
        sigilId: String,
        sigilInventoryEntryId: UUID,
        updatedBy: UUID,
    ): LearnedSkillInstance {
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("sigilId", sigilId)
            addProperty("sigilInventoryEntryId", sigilInventoryEntryId.toString())
            addProperty("updatedBy", updatedBy.toString())
        }
        return mutate("/api/account-skills/$accountId/$learnedSkillId/sigils", body)
    }

    fun forget(accountId: UUID, learnedSkillId: UUID, updatedBy: UUID): LearnedSkillInstance {
        val body = ApiRequestUtil.buildJsonBody {
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
                throw LearnedSkillMutationException(failure, "HTTP ${response.statusCode()} for POST $path: $failure")
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
