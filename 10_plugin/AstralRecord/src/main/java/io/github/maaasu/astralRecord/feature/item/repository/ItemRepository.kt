package io.github.maaasu.astralRecord.feature.item.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.item.model.EquipmentEnchant
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance
import io.github.maaasu.astralRecord.feature.item.model.EquipmentOrbOperationResult
import io.github.maaasu.astralRecord.feature.item.model.EquipmentOrbOperationResultType
import io.github.maaasu.astralRecord.feature.item.model.EquipmentRune
import io.github.maaasu.astralRecord.feature.item.model.EquipmentStatRoll
import io.github.maaasu.astralRecord.feature.item.model.ItemBundle
import io.github.maaasu.astralRecord.feature.item.model.ItemBundleReward
import io.github.maaasu.astralRecord.feature.item.model.ItemBundleOnUse
import io.github.maaasu.astralRecord.feature.item.model.ItemBundleParticle
import io.github.maaasu.astralRecord.feature.item.model.ItemBundleSound
import io.github.maaasu.astralRecord.feature.item.model.ItemAppearance
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumable
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumableEffect
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumableEffectType
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumableOnUse
import io.github.maaasu.astralRecord.feature.item.model.ItemCurrency
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentDurability
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnchantDef
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhance
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceFailAction
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceLevel
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceMaterial
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceStatIncrease
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentClassRequirement
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentRuneDef
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStat
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentTranscendence
import io.github.maaasu.astralRecord.feature.item.model.ItemModel
import io.github.maaasu.astralRecord.feature.item.model.ItemOrb
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffect
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffectType
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEnchantOperation
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbRankMode
import io.github.maaasu.astralRecord.feature.item.model.ItemRune
import io.github.maaasu.astralRecord.feature.item.model.ItemSummary
import io.github.maaasu.astralRecord.feature.item.model.ItemSigil
import io.github.maaasu.astralRecord.feature.item.model.ItemSigilModifier
import io.github.maaasu.astralRecord.feature.item.model.EnchantEntry
import io.github.maaasu.astralRecord.feature.item.model.EnchantEquipmentType
import io.github.maaasu.astralRecord.feature.item.model.EnchantMaster
import io.github.maaasu.astralRecord.feature.item.model.EnchantTarget
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
            Logger.error(LogId.E_5200, e, e.message ?: e.javaClass.simpleName)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.error(LogId.E_5200, e, e.message ?: e.javaClass.simpleName)
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
            Logger.error(LogId.E_5200, e, e.message ?: e.javaClass.simpleName)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.error(LogId.E_5200, e, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    fun updateEquipmentDurability(
        instanceId: String,
        durabilityValue: Int,
        updatedBy: String,
    ): EquipmentInstance? {
        val path = "/api/equipment/durability"
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("equipmentInstanceId", instanceId)
            addProperty("durabilityValue", durabilityValue)
            addProperty("updatedBy", updatedBy)
        }
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return when (response.statusCode()) {
                    200 -> parseEquipmentInstance(response.body())
                    404 -> null
                    else -> {
                        Logger.log(LogId.E_5200, "HTTP ${response.statusCode()} for POST $path")
                        throw IOException("Unexpected status ${response.statusCode()} for POST $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.error(LogId.E_5200, e, e.message ?: e.javaClass.simpleName)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.error(LogId.E_5200, e, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    fun deleteEquipmentInstance(instanceId: String): Boolean {
        val path = "/api/equipment/instances/$instanceId"
        try {
            ApiRequestUtil.buildClient().use { client ->
                val request = ApiRequestUtil.buildRequestBuilder(path)
                    .DELETE()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.discarding())
                return when (response.statusCode()) {
                    204 -> true
                    404 -> false
                    else -> {
                        Logger.log(LogId.E_5200, "HTTP ${response.statusCode()} for DELETE $path")
                        throw IOException("Unexpected status ${response.statusCode()} for DELETE $path")
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.error(LogId.E_5200, e, e.message ?: e.javaClass.simpleName)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.error(LogId.E_5200, e, e.message ?: e.javaClass.simpleName)
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
            Logger.error(LogId.E_5200, e, e.message ?: e.javaClass.simpleName)
            throw RuntimeException(e)
        } catch (e: IOException) {
            Logger.error(LogId.E_5200, e, e.message ?: e.javaClass.simpleName)
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
            appearance = parseAppearance(obj),
            lore = parseLore(obj.getAsJsonArray("lore")),
            unTradeable = obj.get("unTradeable")?.asBoolean ?: false,
            unSellable = obj.get("unSellable")?.asBoolean ?: false,
            bundle = parseBundle(obj),
            currency = parseCurrency(obj),
            equipment = parseEquipment(obj),
            rune = parseRune(obj),
            consumable = parseConsumable(obj),
            sigil = parseSigil(obj),
            orb = parseOrb(obj),
        )
    }

    /** 共通エンチャントマスタを取得します。 */
    fun findEnchantMasterById(enchantMasterId: String): EnchantMaster? {
        val encodedId = URLEncoder.encode(enchantMasterId, StandardCharsets.UTF_8).replace("+", "%20")
        val path = "/api/enchant/$encodedId"
        try {
            ApiRequestUtil.buildClient().use { client ->
                val response = client.send(
                    ApiRequestUtil.buildRequestBuilder(path).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                return when (response.statusCode()) {
                    200 -> parseEnchantMaster(response.body())
                    404 -> null
                    else -> throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }
    }

    /** オーブ支払いと装備更新を同一 operationId でAPIへ要求します。オーブ消費元はAPIが共通順で確定します。 */
    fun applyEquipmentOrbOperation(
        operationId: String,
        accountId: String,
        instanceId: String,
        orbInventoryEntryId: String,
        orbItemId: String,
        runeItemId: String? = null,
        runeSlotIndex: Int? = null,
    ): EquipmentOrbOperationResult? {
        val path = "/api/equipment/orb-operations"
        val body = ApiRequestUtil.buildJsonBody {
            addProperty("operationId", operationId)
            addProperty("accountId", accountId)
            addProperty("equipmentInstanceId", instanceId)
            addProperty("orbInventoryEntryId", orbInventoryEntryId)
            addProperty("orbItemId", orbItemId)
            if (runeItemId != null) addProperty("runeItemId", runeItemId)
            if (runeSlotIndex != null) addProperty("runeSlotIndex", runeSlotIndex)
        }
        try {
            ApiRequestUtil.buildClient().use { client ->
                val response = client.send(
                    ApiRequestUtil.buildRequestBuilder(path)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                return when (response.statusCode()) {
                    200, 409 -> parseOrbOperationResult(response.body())
                    else -> throw IOException("Unexpected status ${response.statusCode()} for POST $path")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }
    }

    /** operationIdに保存されたオーブ装備操作結果を照会します。 */
    fun findEquipmentOrbOperation(operationId: String, accountId: String): EquipmentOrbOperationResult? {
        val encodedAccountId = URLEncoder.encode(accountId, StandardCharsets.UTF_8).replace("+", "%20")
        val path = "/api/equipment/orb-operations/$operationId?account_id=$encodedAccountId"
        try {
            ApiRequestUtil.buildClient().use { client ->
                val response = client.send(
                    ApiRequestUtil.buildRequestBuilder(path).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                return when (response.statusCode()) {
                    200 -> parseOrbOperationResult(response.body())
                    404 -> null
                    else -> throw IOException("Unexpected status ${response.statusCode()} for GET $path")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }
    }

    private fun parseOrb(obj: JsonObject): ItemOrb? {
        val orbObj = parseObjectOrNull(obj, "orb") ?: return null
        val effectObj = parseObjectOrNull(orbObj, "effect") ?: return null
        val type = ItemOrbEffectType.fromApiValue(parseStringOrNull(effectObj, "type")) ?: return null
        val targetSlots = parseStringList(effectObj.getAsJsonArray("targetSlots"))
            .map(ItemEquipmentSlot::fromApiValue)
            .filter { it != ItemEquipmentSlot.UNKNOWN }
        return ItemOrb(ItemOrbEffect(
            type = type,
            targetSlots = targetSlots,
            rank = parseIntOrNull(effectObj, "rank"),
            rankMode = ItemOrbRankMode.fromApiValue(parseStringOrNull(effectObj, "rankMode")),
            repairAmount = parseIntOrNull(effectObj, "repairAmount"),
            repairFull = effectObj.get("repairFull")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            enchantMasterId = normalizeEnchantMasterReference(parseStringOrNull(effectObj, "enchantMasterId")),
            enchantOperation = ItemOrbEnchantOperation.fromApiValue(parseStringOrNull(effectObj, "enchantOperation")),
        ))
    }

    /** Seeder/API と同じく既知の enchant: prefix だけを除去する。 */
    private fun normalizeEnchantMasterReference(reference: String?): String? {
        val normalized = reference?.trim() ?: return null
        return if (normalized.startsWith("enchant:", ignoreCase = true)) {
            normalized.substring("enchant:".length)
        } else {
            normalized
        }
    }

    private fun parseSigil(obj: JsonObject): ItemSigil? {
        val sigilObj = parseObjectOrNull(obj, "sigil") ?: return null
        val equipGroupId = parseStringOrNull(sigilObj, "equipGroupId") ?: return null
        val modifiers = sigilObj.getAsJsonArray("modifiers")?.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val modifier = element.asJsonObject
            val status = parseStringOrNull(modifier, "status") ?: return@mapNotNull null
            val value = parseDoubleOrNull(modifier, "value") ?: return@mapNotNull null
            ItemSigilModifier(status, value)
        }.orEmpty()
        return ItemSigil(equipGroupId, modifiers)
    }

    private fun parseAppearance(obj: JsonObject): ItemAppearance? {
        val appearanceObj = parseObjectOrNull(obj, "appearance") ?: return null
        val color = parseStringOrNull(appearanceObj, "color")
        val potionType = parseStringOrNull(appearanceObj, "potionType")
        if (color == null && potionType == null) {
            return null
        }
        return ItemAppearance(color = color, potionType = potionType)
    }

    private fun parseBundle(obj: JsonObject): ItemBundle? {
        val bundleObj = parseObjectOrNull(obj, "bundle") ?: return null
        val onUseObj = parseObjectOrNull(bundleObj, "onUse")

        return ItemBundle(
            lootTableId = parseStringOrNull(bundleObj, "lootTableId"),
            items = parseArrayOrNull(bundleObj, "items")?.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                val reward = element.asJsonObject
                val itemId = parseStringOrNull(reward, "itemId") ?: return@mapNotNull null
                val amount = parseIntOrNull(reward, "amount") ?: 1
                ItemBundleReward(itemId, amount.coerceAtLeast(1))
            }.orEmpty(),
            gold = parseDoubleOrNull(bundleObj, "gold")?.toLong()?.coerceAtLeast(0L) ?: 0L,
            openTimeTicks = parseLongOrNull(bundleObj, "openTimeTicks") ?: 20L,
            onUse = if (onUseObj != null) {
                ItemBundleOnUse(
                    sound = parseBundleSound(onUseObj),
                    effect = parseStringOrNull(onUseObj, "effect"),
                    particle = parseBundleParticle(onUseObj),
                )
            } else {
                null
            },
        )
    }

    private fun parseBundleSound(obj: JsonObject): ItemBundleSound? {
        val value = obj.get("sound") ?: return null
        if (!value.isJsonObject) return null
        val soundObj = value.asJsonObject
        return ItemBundleSound(
            sound = parseStringOrNull(soundObj, "sound"),
            volume = parseDoubleOrNull(soundObj, "volume"),
            pitch = parseDoubleOrNull(soundObj, "pitch"),
        )
    }

    private fun parseBundleParticle(obj: JsonObject): ItemBundleParticle? {
        val value = obj.get("particle") ?: return null
        if (!value.isJsonObject) return null
        val particleObj = value.asJsonObject
        return ItemBundleParticle(
            particle = parseStringOrNull(particleObj, "particle"),
            count = parseIntOrNull(particleObj, "count"),
            originOffsetX = parseDoubleOrNull(particleObj, "originOffsetX"),
            originOffsetY = parseDoubleOrNull(particleObj, "originOffsetY"),
            originOffsetZ = parseDoubleOrNull(particleObj, "originOffsetZ"),
            offsetX = parseDoubleOrNull(particleObj, "offsetX"),
            offsetY = parseDoubleOrNull(particleObj, "offsetY"),
            offsetZ = parseDoubleOrNull(particleObj, "offsetZ"),
            extra = parseDoubleOrNull(particleObj, "extra"),
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
            val scalarValue = parseStringOrNull(statObj, "value")
            val rawMinValue = valueObj?.let { parseStringOrNull(it, "min") }
                ?: parseStringOrNull(statObj, "min")
                ?: parseRawStatBound(scalarValue, false)
            val rawMaxValue = valueObj?.let { parseStringOrNull(it, "max") }
                ?: parseStringOrNull(statObj, "max")
                ?: parseRawStatBound(scalarValue, true)

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
                rawMin = rawMinValue,
                rawMax = rawMaxValue,
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

        return ItemEquipment(
            slot = ItemEquipmentSlot.fromApiValue(parseStringOrNull(equipmentObj, "slot")),
            handType = ItemEquipmentHandType.fromApiValue(parseStringOrNull(equipmentObj, "handType")),
            tag = parseStringOrNull(equipmentObj, "tag"),
            requiredLevel = equipmentObj.get("requiredLevel")?.asInt ?: 0,
            requiredClasses = parseEquipmentClassRequirements(equipmentObj.getAsJsonArray("requiredClasses")),
            setId = parseStringOrNull(equipmentObj, "setId"),
            stats = stats,
            durability = durability,
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
            targetTags = parseStringList(runeObj.getAsJsonArray("targetTags")),
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
                successRate = parseDoubleOrNull(lvlObj, "successRate") ?: 1.0,
                failAction = ItemEquipmentEnhanceFailAction.fromApiValue(parseStringOrNull(lvlObj, "failAction")),
                failTargetLevel = parseIntOrNull(lvlObj, "failTargetLevel"),
            )
        }
        return ItemEquipmentEnhance(maxLevel = maxLevel, levels = levels)
    }

    private fun parseEquipmentClassRequirements(array: JsonArray?): List<ItemEquipmentClassRequirement> {
        if (array == null) {
            return emptyList()
        }
        return array.mapNotNull { element ->
            if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                return@mapNotNull ItemEquipmentClassRequirement(element.asString, 1)
            }
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            val classId = parseStringOrNull(obj, "classId")
                ?: parseStringOrNull(obj, "class")
                ?: return@mapNotNull null
            ItemEquipmentClassRequirement(
                classId = classId,
                level = max(1, parseIntOrNull(obj, "level") ?: 1),
            )
        }
    }

    private fun parseEquipmentEnhanceMaterials(array: JsonArray?): List<ItemEquipmentEnhanceMaterial> {
        if (array == null) {
            return emptyList()
        }
        return array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            val itemId = parseStringOrNull(obj, "itemId") ?: return@mapNotNull null
            ItemEquipmentEnhanceMaterial(
                itemId = itemId,
                amount = parseIntOrNull(obj, "amount") ?: 1,
            )
        }
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
        return ItemEquipmentRuneDef(maxSlotsRaw = maxSlotsRaw)
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
                requiredEnhanceLevel = max(0, parseIntOrNull(tObj, "requiredEnhanceLevel") ?: 0),
                requiredMaterials = parseEquipmentEnhanceMaterials(tObj.getAsJsonArray("requiredMaterials")),
                requiredCurrency = max(0, parseIntOrNull(tObj, "requiredCurrency") ?: 0),
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
                usingSound = onUseObj.get("usingSound")?.takeIf { !it.isJsonNull }?.asString,
                sound = onUseObj.get("sound")?.takeIf { !it.isJsonNull }?.asString,
                effect = onUseObj.get("effect")?.takeIf { !it.isJsonNull }?.asString,
                amount = onUseObj.get("amount")?.asInt ?: 1,
                useTimeTicks = onUseObj.get("useTimeTicks")?.takeIf { !it.isJsonNull }?.asLong ?: 40L,
                cooldownTicks = onUseObj.get("cooldownTicks")?.takeIf { !it.isJsonNull }?.asLong ?: 40L,
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
        if (element == null || element.isJsonNull || !element.isJsonPrimitive) {
            return null
        }
        return element.asString
    }

    /** ステータス値のスカラ表現から、指定された側の生値を取得します。 */
    private fun parseRawStatBound(value: String?, upper: Boolean): String? {
        if (value == null) {
            return null
        }
        val parts = value.trim().split(Regex("[~～]"), limit = 2)
        return if (upper && parts.size == 2) {
            parts[1].trim()
        } else {
            parts[0].trim()
        }
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
        if (element == null || element.isJsonNull || !element.isJsonPrimitive) {
            return null
        }
        return runCatching { element.asInt }.getOrNull()
    }

    private fun parseLongOrNull(obj: JsonObject, key: String): Long? {
        if (!obj.has(key)) {
            return null
        }
        val element = obj.get(key)
        if (element == null || element.isJsonNull || !element.isJsonPrimitive) {
            return null
        }
        return runCatching { element.asLong }.getOrNull()
    }

    private fun parseArrayOrNull(obj: JsonObject, key: String): JsonArray? {
        if (!obj.has(key)) {
            return null
        }
        val element = obj.get(key)
        return if (element == null || element.isJsonNull || !element.isJsonArray) {
            null
        } else {
            element.asJsonArray
        }
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
        return parseEquipmentInstance(obj)
    }

    private fun parseEquipmentInstance(obj: JsonObject): EquipmentInstance {
        return EquipmentInstance(
            equipmentInstanceId = obj.get("equipmentInstanceId").asString,
            accountId = obj.get("accountId").asString,
            itemId = obj.get("itemId").asString,
            enhanceLevel = parseIntOrNull(obj, "enhanceLevel") ?: 0,
            runeMaxSlots = parseIntOrNull(obj, "runeMaxSlots") ?: 0,
            transcendenceRank = parseIntOrNull(obj, "transcendenceRank") ?: 0,
            durabilityMax = parseIntOrNull(obj, "durabilityMax") ?: 0,
            durabilityValue = parseIntOrNull(obj, "durabilityValue") ?: 0,
            createdAt = parseStringOrNull(obj, "createdAt") ?: "",
            updatedAt = parseStringOrNull(obj, "updatedAt") ?: "",
            statRolls = parseStatRolls(parseArrayOrNull(obj, "statRolls")),
            enchants = parseEnchants(parseArrayOrNull(obj, "enchants")),
            runes = parseRunes(parseArrayOrNull(obj, "runes")),
        )
    }

    private fun parseOrbOperationResult(json: String): EquipmentOrbOperationResult {
        val obj = JsonParser.parseString(json).asJsonObject
        val equipment = parseObjectOrNull(obj, "equipment")?.let(::parseEquipmentInstance)
        val failAction = parseStringOrNull(obj, "failAction")?.let {
            ItemEquipmentEnhanceFailAction.fromApiValue(it)
        }
        return EquipmentOrbOperationResult(
            operationId = parseStringOrNull(obj, "operationId") ?: "",
            result = EquipmentOrbOperationResultType.fromApiValue(parseStringOrNull(obj, "result")),
            operationType = parseStringOrNull(obj, "operationType") ?: "",
            equipment = equipment,
            targetAvailable = obj.get("targetAvailable")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            affectedInventoryEntryIds = parseStringList(parseArrayOrNull(obj, "affectedInventoryEntryIds")),
            paymentConsumed = obj.get("paymentConsumed")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            enhancementSucceeded = obj.get("enhancementSucceeded")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            failAction = failAction,
            successRate = obj.get("successRate")?.takeIf { !it.isJsonNull }?.asDouble,
            repairedAmount = parseIntOrNull(obj, "repairedAmount"),
            transitionName = parseStringOrNull(obj, "transitionName"),
        )
    }

    private fun parseEnchantMaster(json: String): EnchantMaster {
        val obj = JsonParser.parseString(json).asJsonObject
        val targets = obj.getAsJsonArray("targets")?.mapNotNull { targetElement ->
            if (!targetElement.isJsonObject) return@mapNotNull null
            val targetObj = targetElement.asJsonObject
            val equipmentType = runCatching {
                EnchantEquipmentType.valueOf(parseStringOrNull(targetObj, "equipmentType")?.uppercase() ?: "")
            }.getOrNull() ?: return@mapNotNull null
            val entries = targetObj.getAsJsonArray("entries")?.mapNotNull { entryElement ->
                if (!entryElement.isJsonObject) return@mapNotNull null
                val entryObj = entryElement.asJsonObject
                EnchantEntry(
                    effectId = parseStringOrNull(entryObj, "effectId") ?: return@mapNotNull null,
                    status = parseStringOrNull(entryObj, "status") ?: return@mapNotNull null,
                    type = parseStringOrNull(entryObj, "type") ?: return@mapNotNull null,
                    value = parseStringOrNull(entryObj, "value") ?: return@mapNotNull null,
                    weight = parseIntOrNull(entryObj, "weight") ?: 1,
                )
            }.orEmpty()
            EnchantTarget(equipmentType, entries)
        }.orEmpty()
        return EnchantMaster(
            schemaVersion = parseIntOrNull(obj, "schemaVersion") ?: 1,
            id = parseStringOrNull(obj, "id") ?: "",
            targets = targets,
        )
    }

    private fun parseStatRolls(array: JsonArray?): List<EquipmentStatRoll> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            EquipmentStatRoll(
                statRollId = parseStringOrNull(obj, "statRollId") ?: return@mapNotNull null,
                status = parseStringOrNull(obj, "status") ?: return@mapNotNull null,
                min = parseStringOrNull(obj, "min") ?: "0",
                max = parseStringOrNull(obj, "max") ?: "0",
                sortOrder = parseIntOrNull(obj, "sortOrder") ?: 0,
            )
        }
    }

    private fun parseEnchants(array: JsonArray?): List<EquipmentEnchant> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            EquipmentEnchant(
                enchantId = parseStringOrNull(obj, "enchantId") ?: return@mapNotNull null,
                equipmentInstanceId = parseStringOrNull(obj, "equipmentInstanceId") ?: return@mapNotNull null,
                slotIndex = parseIntOrNull(obj, "slotIndex") ?: 0,
                enchantMasterId = parseStringOrNull(obj, "enchantMasterId") ?: return@mapNotNull null,
                effectId = parseStringOrNull(obj, "effectId") ?: return@mapNotNull null,
                status = parseStringOrNull(obj, "status") ?: return@mapNotNull null,
                type = parseStringOrNull(obj, "type") ?: return@mapNotNull null,
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
                runeId = parseStringOrNull(obj, "runeId") ?: return@mapNotNull null,
                equipmentInstanceId = parseStringOrNull(obj, "equipmentInstanceId") ?: return@mapNotNull null,
                slotIndex = parseIntOrNull(obj, "slotIndex") ?: 0,
                itemId = parseStringOrNull(obj, "itemId") ?: return@mapNotNull null,
            )
        }
    }

}
