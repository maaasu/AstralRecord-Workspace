package io.github.maaasu.astralRecord.feature.item.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.item.model.EquipmentEnchant
import io.github.maaasu.astralRecord.feature.item.model.EquipmentEnchantPool
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance
import io.github.maaasu.astralRecord.feature.item.model.EquipmentRune
import io.github.maaasu.astralRecord.feature.item.model.EquipmentStatRoll
import io.github.maaasu.astralRecord.feature.item.model.RuneInstance
import io.github.maaasu.astralRecord.feature.item.model.RuneStatRoll
import io.github.maaasu.astralRecord.feature.item.model.ItemBundle
import io.github.maaasu.astralRecord.feature.item.model.ItemBundleOnUse
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumable
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumableEffect
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumableEffectType
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumableOnUse
import io.github.maaasu.astralRecord.feature.item.model.ItemCurrency
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentDurability
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnchantDef
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhance
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceLevel
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceStatIncrease
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentOnUse
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentRuneDef
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStat
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentTranscendence
import io.github.maaasu.astralRecord.feature.item.model.ItemModel
import io.github.maaasu.astralRecord.feature.item.model.ItemRune
import io.github.maaasu.astralRecord.feature.item.model.ItemSummary
import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil
import java.io.IOException
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.min

/**
 * AstralRecord API を通じてアイテム定義を取得するリポジトリ。
 */
class ItemRepository {

    /**
     * 全カテゴリのアイテム一覧（id/category）を取得します。
     * GET /api/item
     */
    fun findAll(): List<ItemSummary> {
        val path = "/api/item"
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> parseSummaryList(response.body())
                    else -> {
                        val message = "HTTP ${response.statusCode()} for GET $path"
                        Logger.log(LogId.E_5201, message)
                        throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5201, e, e.message ?: "Interrupted while GET $path")
            throw RuntimeException(e)
        }
    }

    /**
     * 指定カテゴリの全アイテムを取得します。
     * 一覧API（/api/item）でカテゴリを抽出し、詳細APIで展開します。
     */
    fun findAllByCategory(category: String): List<ItemModel> {
        val normalizedCategory = category.trim()
        if (normalizedCategory.isBlank()) {
            return emptyList()
        }

        val summaries = findAll().filter { it.category.equals(normalizedCategory, ignoreCase = true) }
        val items = mutableListOf<ItemModel>()
        for (summary in summaries) {
            val item = findById(summary.id, summary.category)
            if (item != null) {
                items += item
            }
        }

        Logger.log(LogId.D_5202, normalizedCategory, items.size)
        return items
    }

    /**
     * アイテムマスタデータを取得します。全カテゴリ（equipment を含む）で item API を使用します。
     * 起動時の初回ロード・マスタデータ参照用途に使用してください。
     * GET /api/item/{itemId}
     */
    fun findById(itemId: String, category: String): ItemModel? {
        val encodedItemId = URLEncoder.encode(itemId, StandardCharsets.UTF_8).replace("+", "%20")
        val path = "/api/item/$encodedItemId"
        return fetchItem(path, itemId, category.trim().lowercase())
    }

    /**
     * 装備インスタンスを新規作成します。
     * ステータス乱数ロールは API 側で解決され、結果を返します。
     * POST /api/equipment/instances
     *
     * @param equipmentId アイテムテンプレート ID
     * @param accountId   所有アカウント ID（UUID 文字列）
     * @param source      取得元（例: "loot_drop"）
     * @param createdBy   作成者アカウント ID（UUID 文字列）
     */
    fun createEquipmentInstance(
        equipmentId: String,
        accountId: String,
        source: String,
        createdBy: String,
    ): EquipmentInstance? {
        val path = "/api/equipment/instances"
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("equipmentId", equipmentId)
            addProperty("accountId", accountId)
            addProperty("source", source)
            addProperty("createdBy", createdBy)
        }
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200, 201 -> parseEquipmentInstance(response.body())
                    else -> {
                        Logger.log(LogId.E_5200, "HTTP ${response.statusCode()} for POST $path")
                        throw IOException("Unexpected status ${response.statusCode()} for POST $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5200, e)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.log(LogId.E_5200, e)
            throw e
        }
    }

    /**
     * 指定した装備インスタンスを取得します。
     * GET /api/equipment/instances/{instanceId}
     *
     * @param instanceId 装備インスタンス ID（UUID 文字列）
     */
    fun findEquipmentInstanceById(instanceId: String): EquipmentInstance? {        val path = "/api/equipment/instances/$instanceId"
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> parseEquipmentInstance(response.body())
                    404 -> {
                        Logger.log(LogId.W_5200, "equipment", instanceId)
                        null
                    }
                    else -> {
                        Logger.log(LogId.E_5200, "HTTP ${response.statusCode()} for GET $path")
                        throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5200, e)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.log(LogId.E_5200, e)
            throw e
        }
    }

    /**
     * ルーンインスタンスを新規作成します。
     * ステータス乱数ロールは API 側で解決され、確定値として返されます。
     * POST /api/rune/instances
     *
     * @param runeId    アイテムテンプレート ID
     * @param accountId 所有アカウント ID（UUID 文字列）
     * @param source    取得元（例: "loot_drop"）
     * @param createdBy 作成者アカウント ID（UUID 文字列）
     */
    fun createRuneInstance(
        runeId: String,
        accountId: String,
        source: String,
        createdBy: String,
    ): RuneInstance? {
        val path = "/api/rune/instances"
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("runeId", runeId)
            addProperty("accountId", accountId)
            addProperty("source", source)
            addProperty("createdBy", createdBy)
        }
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200, 201 -> parseRuneInstance(response.body())
                    else -> {
                        Logger.log(LogId.E_5200, "HTTP ${response.statusCode()} for POST $path")
                        throw IOException("Unexpected status ${response.statusCode()} for POST $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5200, e)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.log(LogId.E_5200, e)
            throw e
        }
    }

    /**
     * 指定したルーンインスタンスを取得します。
     * GET /api/rune/instances/{instanceId}
     *
     * @param instanceId ルーンインスタンス ID（UUID 文字列）
     */
    fun findRuneInstanceById(instanceId: String): RuneInstance? {
        val path = "/api/rune/instances/$instanceId"
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> parseRuneInstance(response.body())
                    404 -> {
                        Logger.log(LogId.W_5200, "rune", instanceId)
                        null
                    }
                    else -> {
                        Logger.log(LogId.E_5200, "HTTP ${response.statusCode()} for GET $path")
                        throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5200, e)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.log(LogId.E_5200, e)
            throw e
        }
    }

    private fun fetchItem(path: String, itemId: String, categoryForLog: String): ItemModel? {
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> parseItem(response.body())
                    404 -> {
                        Logger.log(LogId.W_5200, categoryForLog, itemId)
                        null
                    }
                    else -> {
                        Logger.log(LogId.E_5200, "HTTP ${response.statusCode()} for GET $path")
                        throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.log(LogId.E_5200, e)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.log(LogId.E_5200, e)
            throw e
        }
    }

    private fun parseItem(json: String): ItemModel {
        val obj = JsonParser.parseString(json).asJsonObject
        return parseItem(obj)
    }

    private fun parseSummaryList(json: String): List<ItemSummary> {
        val array = JsonParser.parseString(json).asJsonArray
        return array.mapNotNull { element ->
            if (!element.isJsonObject) {
                return@mapNotNull null
            }
            val obj = element.asJsonObject
            val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            val category = obj.get("category")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            ItemSummary(id = id, category = category)
        }
    }

    private fun parseItemList(json: String): List<ItemModel> {
        val array = JsonParser.parseString(json).asJsonArray
        return array.map { parseItem(it.asJsonObject) }
    }

    private fun parseItem(obj: JsonObject): ItemModel {
        val category = obj.get("category")?.asString ?: "unknown"
        return ItemModel(
            schemaVersion = obj.get("schemaVersion")?.asInt ?: 1,
            id = obj.get("id").asString,
            category = category,
            name = obj.get("name").asString,
            icon = obj.get("icon").asString,
            rarity = obj.get("rarity").asString,
            maxStack = obj.get("maxStack")?.asInt ?: 64,
            saleValue = obj.get("saleValue")?.asInt ?: 0,
            customModelData = if (obj.has("customModelData") && !obj.get("customModelData").isJsonNull) {
                obj.get("customModelData").asInt
            } else {
                null
            },
            lore = parseLore(obj.getAsJsonArray("lore")),
            unTradeable = obj.get("unTradeable")?.asBoolean ?: false,
            unSellable = obj.get("unSellable")?.asBoolean ?: false,
            bundle = parseBundle(obj),
            currency = parseCurrency(obj),
            equipment = parseEquipment(obj),
            rune = parseRune(obj),
            consumable = parseConsumable(obj),
        )
    }

    private fun parseBundle(obj: JsonObject): ItemBundle? {
        val bundleObj = parseObjectOrNull(obj, "bundle") ?: return null
        val onUseObj = parseObjectOrNull(bundleObj, "onUse")

        return ItemBundle(
            lootTableId = parseStringOrNull(bundleObj, "lootTableId"),
            onUse = if (onUseObj != null) {
                ItemBundleOnUse(
                    sound = parseStringOrNull(onUseObj, "sound"),
                    effect = parseStringOrNull(onUseObj, "effect"),
                    particle = parseStringOrNull(onUseObj, "particle"),
                )
            } else {
                null
            },
        )
    }

    private fun parseCurrency(obj: JsonObject): ItemCurrency? {
        val currencyObj = parseObjectOrNull(obj, "currency") ?: return null
        return ItemCurrency(
            type = parseStringOrNull(currencyObj, "type"),
            group = parseStringOrNull(currencyObj, "group"),
            expiresAt = parseStringOrNull(currencyObj, "expiresAt"),
        )
    }

    private fun parseEquipment(obj: JsonObject): ItemEquipment? {
        val equipmentObj = parseObjectOrNull(obj, "equipment") ?: return null

        val statsArray = equipmentObj.getAsJsonArray("stats") ?: JsonArray()
        val stats = statsArray.mapNotNull { element ->
            if (!element.isJsonObject) {
                return@mapNotNull null
            }

            val statObj = element.asJsonObject
            val status = parseStringOrNull(statObj, "status") ?: return@mapNotNull null
            val valueObj = parseObjectOrNull(statObj, "value")

            val minValue = parseDoubleOrNull(statObj, "min")
                ?: valueObj?.let { parseDoubleOrNull(it, "min") }
                ?: valueObj?.let { parseLowerBoundOrNull(it, "min") }
                ?: parseLowerBoundOrNull(statObj, "value")
            val maxValue = parseDoubleOrNull(statObj, "max")
                ?: valueObj?.let { parseDoubleOrNull(it, "max") }
                ?: valueObj?.let { parseUpperBoundOrNull(it, "max") }
                ?: parseUpperBoundOrNull(statObj, "value")
            val singleValue = parseDoubleOrNull(statObj, "value")
                ?: parseLowerBoundOrNull(statObj, "value")

            val resolvedMin = minValue ?: maxValue ?: singleValue ?: return@mapNotNull null
            val resolvedMax = maxValue ?: minValue ?: singleValue ?: resolvedMin

            ItemEquipmentStat(
                status = status,
                type = ItemEquipmentStatType.fromApiValue(parseStringOrNull(statObj, "type")),
                min = min(resolvedMin, resolvedMax),
                max = max(resolvedMin, resolvedMax),
            )
        }

        val durabilityObj = parseObjectOrNull(equipmentObj, "durability")
        val durability = if (durabilityObj != null && durabilityObj.has("max") && !durabilityObj.get("max").isJsonNull) {
            ItemEquipmentDurability(
                max = durabilityObj.get("max").asInt,
                consume = durabilityObj.get("consume")?.asInt ?: 1,
            )
        } else {
            null
        }

        val onUseObj = parseObjectOrNull(equipmentObj, "onUse")
        val onUse = if (onUseObj != null) {
            ItemEquipmentOnUse(
                leftClickCooldownTicks = parseIntOrNull(onUseObj, "leftClickCooldownTicks"),
                leftClickSkillId = parseStringOrNull(onUseObj, "leftClickSkillId"),
                rightClickCooldownTicks = parseIntOrNull(onUseObj, "rightClickCooldownTicks")
                    ?: parseIntOrNull(onUseObj, "RightClickCooldownTicks"),
                rightClickSkillId = parseStringOrNull(onUseObj, "rightClickSkillId")
                    ?: parseStringOrNull(onUseObj, "RightClickSkillId"),
            )
        } else {
            null
        }

        return ItemEquipment(
            slot = ItemEquipmentSlot.fromApiValue(parseStringOrNull(equipmentObj, "slot")),
            handType = ItemEquipmentHandType.fromApiValue(parseStringOrNull(equipmentObj, "handType")),
            requiredLevel = equipmentObj.get("requiredLevel")?.asInt ?: 0,
            requiredClasses = parseStringList(equipmentObj.getAsJsonArray("requiredClasses")),
            setId = parseStringOrNull(equipmentObj, "setId"),
            stats = stats,
            durability = durability,
            onUse = onUse,
            skills = parseStringList(equipmentObj.getAsJsonArray("skills")),
            enhance = parseEquipmentEnhance(equipmentObj),
            enchant = parseEquipmentEnchantDef(equipmentObj),
            rune = parseEquipmentRuneDef(equipmentObj),
            transcendence = parseEquipmentTranscendence(equipmentObj),
        )
    }

    private fun parseRune(obj: JsonObject): ItemRune? {
        val runeObj = parseObjectOrNull(obj, "rune") ?: return null
        return ItemRune(
            targetSlots = parseStringList(runeObj.getAsJsonArray("targetSlots")),
            requiredEnhanceLevel = runeObj.get("requiredEnhanceLevel")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
            stats = parseRuneDefinitionStats(runeObj.getAsJsonArray("stats")),
            skills = parseStringList(runeObj.getAsJsonArray("skills")),
        )
    }

    private fun parseRuneDefinitionStats(array: JsonArray?): List<ItemEquipmentStat> {
        if (array == null) {
            return emptyList()
        }
        return array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val statObj = element.asJsonObject
            val status = parseStringOrNull(statObj, "status") ?: return@mapNotNull null
            val minValue = parseLowerBoundOrNull(statObj, "value") ?: return@mapNotNull null
            val maxValue = parseUpperBoundOrNull(statObj, "value") ?: minValue
            ItemEquipmentStat(
                status = status,
                type = ItemEquipmentStatType.fromApiValue(parseStringOrNull(statObj, "type")),
                min = minValue,
                max = maxValue,
            )
        }
    }

    private fun parseEquipmentEnhance(equipmentObj: JsonObject): ItemEquipmentEnhance? {
        val enhanceObj = parseObjectOrNull(equipmentObj, "enhance") ?: return null
        val maxLevel = enhanceObj.get("maxLevel")?.asInt ?: 0
        val levelsArray = enhanceObj.getAsJsonArray("levels") ?: JsonArray()
        val levels = levelsArray.mapNotNull { elem ->
            if (!elem.isJsonObject) return@mapNotNull null
            val lvlObj = elem.asJsonObject
            val level = lvlObj.get("level")?.asInt ?: return@mapNotNull null
            val statIncreaseArray = lvlObj.getAsJsonArray("statIncrease") ?: JsonArray()
            val statIncrease = statIncreaseArray.mapNotNull { si ->
                if (!si.isJsonObject) return@mapNotNull null
                val siObj = si.asJsonObject
                val status = parseStringOrNull(siObj, "status") ?: return@mapNotNull null
                val minVal = parseLowerBoundOrNull(siObj, "value") ?: 0.0
                val maxVal = parseUpperBoundOrNull(siObj, "value") ?: minVal
                ItemEquipmentEnhanceStatIncrease(
                    status = status,
                    type = ItemEquipmentStatType.fromApiValue(parseStringOrNull(siObj, "type")),
                    min = minVal,
                    max = maxVal,
                )
            }
            ItemEquipmentEnhanceLevel(
                level = level,
                statIncrease = statIncrease,
                durabilityBonus = parseIntOrNull(lvlObj, "durabilityBonus"),
            )
        }
        return ItemEquipmentEnhance(maxLevel = maxLevel, levels = levels)
    }

    private fun parseEquipmentEnchantDef(equipmentObj: JsonObject): ItemEquipmentEnchantDef? {
        val enchantObj = parseObjectOrNull(equipmentObj, "enchant") ?: return null
        return ItemEquipmentEnchantDef(
            maxSlots = enchantObj.get("maxSlots")?.asInt ?: 1,
        )
    }

    private fun parseEquipmentRuneDef(equipmentObj: JsonObject): ItemEquipmentRuneDef? {
        val runeObj = parseObjectOrNull(equipmentObj, "rune") ?: return null
        val maxSlotsRaw = runeObj.get("maxSlots")?.takeIf { !it.isJsonNull }?.asString ?: "0"
        return ItemEquipmentRuneDef(
            maxSlotsRaw = maxSlotsRaw,
            allowedRuneIds = parseStringList(runeObj.getAsJsonArray("allowedRuneIds")),
        )
    }

    private fun parseEquipmentTranscendence(equipmentObj: JsonObject): List<ItemEquipmentTranscendence> {
        val array = equipmentObj.getAsJsonArray("transcendence") ?: return emptyList()
        return array.mapNotNull { elem ->
            if (!elem.isJsonObject) return@mapNotNull null
            val tObj = elem.asJsonObject
            val overridesObj = parseObjectOrNull(tObj, "overrides")
            val overridesEnhanceObj = overridesObj?.let { parseObjectOrNull(it, "enhance") }
            val overridesEnchantObj = overridesObj?.let { parseObjectOrNull(it, "enchant") }
            ItemEquipmentTranscendence(
                name = parseStringOrNull(tObj, "name"),
                rank = tObj.get("rank")?.asInt ?: 0,
                overridesName = overridesObj?.let { parseStringOrNull(it, "name") },
                overridesEnhanceMaxLevel = overridesEnhanceObj?.let { parseIntOrNull(it, "maxLevel") },
                overridesEnchantMaxSlots = overridesEnchantObj?.let { parseIntOrNull(it, "maxSlots") },
            )
        }
    }

    private fun parseLore(array: JsonArray?): List<String> {
        if (array == null) {
            return emptyList()
        }

        return array.mapNotNull {
            if (it.isJsonPrimitive) it.asString else null
        }
    }

    private fun parseConsumable(obj: JsonObject): ItemConsumable? {
        val consumableObj = parseObjectOrNull(obj, "consumable") ?: return null

        val onUseObj = parseObjectOrNull(consumableObj, "onUse")
        val onUse = if (onUseObj != null) {
            ItemConsumableOnUse(
                sound = onUseObj.get("sound")?.takeIf { !it.isJsonNull }?.asString,
                effect = onUseObj.get("effect")?.takeIf { !it.isJsonNull }?.asString,
                amount = onUseObj.get("amount")?.asInt ?: 1,
            )
        } else {
            null
        }

        val effectsArray = consumableObj.getAsJsonArray("effects") ?: JsonArray()
        val effects = effectsArray.mapNotNull { element ->
            if (!element.isJsonObject) {
                return@mapNotNull null
            }

            val effectObj = element.asJsonObject
            ItemConsumableEffect(
                type = ItemConsumableEffectType.fromApiValue(effectObj.get("type")?.asString),
                rate = effectObj.get("rate")?.asDouble ?: 100.0,
                value = effectObj.get("value")?.takeIf { !it.isJsonNull }?.asDouble,
                status = effectObj.get("status")?.takeIf { !it.isJsonNull }?.asString,
                isPercent = effectObj.get("isPercent")?.asBoolean ?: false,
                buffId = effectObj.get("buffId")?.takeIf { !it.isJsonNull }?.asString,
            )
        }

        return ItemConsumable(
            onUse = onUse,
            effects = effects,
        )
    }

    private fun parseStringList(array: JsonArray?): List<String> {
        if (array == null) {
            return emptyList()
        }

        return array.mapNotNull { element ->
            if (element.isJsonPrimitive) element.asString else null
        }
    }

    private fun parseStringOrNull(obj: JsonObject, key: String): String? {
        if (!obj.has(key)) {
            return null
        }
        val element = obj.get(key)
        if (element == null || element.isJsonNull) {
            return null
        }
        return element.asString
    }

    private fun parseObjectOrNull(obj: JsonObject, key: String): JsonObject? {
        if (!obj.has(key)) {
            return null
        }

        val element = obj.get(key)
        if (element == null || element.isJsonNull || !element.isJsonObject) {
            return null
        }

        return element.asJsonObject
    }

    private fun parseIntOrNull(obj: JsonObject, key: String): Int? {
        if (!obj.has(key)) {
            return null
        }
        val element = obj.get(key)
        if (element == null || element.isJsonNull) {
            return null
        }
        return element.asInt
    }

    private fun parseDoubleOrNull(obj: JsonObject, key: String): Double? {
        if (!obj.has(key)) {
            return null
        }

        val element = obj.get(key)
        if (element == null || element.isJsonNull || !element.isJsonPrimitive) {
            return null
        }

        return runCatching { element.asDouble }.getOrNull()
    }

    private fun parseLowerBoundOrNull(obj: JsonObject, key: String): Double? {
        if (!obj.has(key)) {
            return null
        }

        val element = obj.get(key)
        if (element == null || element.isJsonNull || !element.isJsonPrimitive) {
            return null
        }

        val raw = element.asString.trim()
        val parts = raw.split("~", limit = 2)
        return runCatching { parts.first().trim().toDouble() }.getOrNull()
    }

    private fun parseUpperBoundOrNull(obj: JsonObject, key: String): Double? {
        if (!obj.has(key)) {
            return null
        }

        val element = obj.get(key)
        if (element == null || element.isJsonNull || !element.isJsonPrimitive) {
            return null
        }

        val raw = element.asString.trim()
        val parts = raw.split("~", limit = 2)
        val target = if (parts.size >= 2) parts[1] else parts[0]
        return runCatching { target.trim().toDouble() }.getOrNull()
    }

    // -------------------------------------------------------
    // EquipmentInstance パーサ
    // -------------------------------------------------------

    private fun parseEquipmentInstance(json: String): EquipmentInstance {
        val obj = JsonParser.parseString(json).asJsonObject
        return EquipmentInstance(
            equipmentInstanceId = obj.get("equipmentInstanceId").asString,
            accountId = obj.get("accountId").asString,
            itemId = obj.get("itemId").asString,
            enhanceLevel = obj.get("enhanceLevel")?.asInt ?: 0,
            runeMaxSlots = obj.get("runeMaxSlots")?.asInt ?: 0,
            transcendenceRank = obj.get("transcendenceRank")?.asInt ?: 0,
            durabilityMax = obj.get("durabilityMax")?.asInt ?: 0,
            durabilityValue = obj.get("durabilityValue")?.asInt ?: 0,
            createdAt = obj.get("createdAt")?.asString ?: "",
            updatedAt = obj.get("updatedAt")?.asString ?: "",
            statRolls = parseStatRolls(obj.getAsJsonArray("statRolls")),
            enchants = parseEnchants(obj.getAsJsonArray("enchants")),
            runes = parseRunes(obj.getAsJsonArray("runes")),
            enchantPools = parseEnchantPools(obj.getAsJsonArray("enchantPools")),
        )
    }

    private fun parseStatRolls(array: JsonArray?): List<EquipmentStatRoll> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            EquipmentStatRoll(
                statRollId = obj.get("statRollId")?.asString ?: return@mapNotNull null,
                status = obj.get("status")?.asString ?: return@mapNotNull null,
                min = obj.get("min")?.asString ?: "0",
                max = obj.get("max")?.asString ?: "0",
                sortOrder = obj.get("sortOrder")?.asInt ?: 0,
            )
        }
    }

    private fun parseEnchants(array: JsonArray?): List<EquipmentEnchant> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            EquipmentEnchant(
                enchantId = obj.get("enchantId")?.asString ?: return@mapNotNull null,
                equipmentInstanceId = obj.get("equipmentInstanceId")?.asString ?: return@mapNotNull null,
                slotIndex = obj.get("slotIndex")?.asInt ?: 0,
                poolIndex = obj.get("poolIndex")?.asInt ?: 0,
                status = obj.get("status")?.asString ?: return@mapNotNull null,
                type = obj.get("type")?.asString ?: return@mapNotNull null,
                value = obj.get("value")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0,
            )
        }
    }

    private fun parseRunes(array: JsonArray?): List<EquipmentRune> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            EquipmentRune(
                runeId = obj.get("runeId")?.asString ?: return@mapNotNull null,
                equipmentInstanceId = obj.get("equipmentInstanceId")?.asString ?: return@mapNotNull null,
                slotIndex = obj.get("slotIndex")?.asInt ?: 0,
                itemId = obj.get("itemId")?.asString ?: return@mapNotNull null,
            )
        }
    }

    private fun parseEnchantPools(array: JsonArray?): List<EquipmentEnchantPool> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            EquipmentEnchantPool(
                poolIndex = obj.get("poolIndex")?.asInt ?: return@mapNotNull null,
                recipeId = parseStringOrNull(obj, "recipeId"),
                requiredMaterialItemId = obj.get("requiredMaterialItemId")?.asString ?: return@mapNotNull null,
                requiredMaterialAmount = obj.get("requiredMaterialAmount")?.asInt ?: 0,
                requiredCurrency = obj.get("requiredCurrency")?.asInt ?: 0,
            )
        }
    }

    // -------------------------------------------------------
    // RuneInstance パーサ
    // -------------------------------------------------------

    private fun parseRuneInstance(json: String): RuneInstance {
        val obj = JsonParser.parseString(json).asJsonObject
        return RuneInstance(
            runeInstanceId = obj.get("runeInstanceId").asString,
            accountId = obj.get("accountId").asString,
            itemId = obj.get("itemId").asString,
            createdAt = obj.get("createdAt")?.asString ?: "",
            updatedAt = obj.get("updatedAt")?.asString ?: "",
            statRolls = parseRuneStatRolls(obj.getAsJsonArray("statRolls")),
        )
    }

    private fun parseRuneStatRolls(array: JsonArray?): List<RuneStatRoll> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            RuneStatRoll(
                statRollId = obj.get("statRollId")?.asString ?: return@mapNotNull null,
                status = obj.get("status")?.asString ?: return@mapNotNull null,
                type = obj.get("type")?.asString ?: return@mapNotNull null,
                value = obj.get("value")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null,
                sortOrder = obj.get("sortOrder")?.asInt ?: 0,
            )
        }
    }
}

