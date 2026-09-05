package io.github.maaasu.astralRecord.feature.inventory.repository

import com.google.gson.JsonElement
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryOperationSnapshot
import java.util.UUID

/** 操作応答の任意snapshotを共通entry形式で読み込む。HTTP通信は行わない。 */
object InventoryOperationSnapshotParser {
    private val entries = InventoryRepository()

    /**
     * 旧APIや不完全な応答はnullを返し、呼出元の正本GETへフォールバックする。
     * 通信や共有状態の変更は行わず、不正値の変換例外は呼出元へ送出しない。
     * @param element API応答のinventorySnapshot値。旧APIまたは未収録はnull
     * @return 必須値・ID整合を検証したsnapshot。不正または未収録ならnull
     */
    @JvmStatic
    fun parse(element: JsonElement?): InventoryOperationSnapshot? = runCatching {
        val obj = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val covered = obj.getAsJsonArray("coveredEntryIds").map { UUID.fromString(it.asString) }.toSet()
        val rows = obj.getAsJsonArray("entries").map { entries.parseInventoryEntryModel(it.toString()) }
        val currencyId = obj.get("currencyInventoryId")?.takeIf { !it.isJsonNull }?.asString?.let(UUID::fromString)
        val currencyRows = obj.getAsJsonArray("currencyEntries").map { entries.parseInventoryEntryModel(it.toString()) }
        require(rows.map { it.inventoryEntryId }.toSet().size == rows.size)
        require(rows.all { it.inventoryEntryId in covered && !it.isDeleted })
        require(currencyRows.map { it.inventoryEntryId }.toSet().size == currencyRows.size)
        require(currencyRows.all { it.inventoryId == currencyId && !it.isDeleted })
        InventoryOperationSnapshot(UUID.fromString(obj.get("accountId").asString), covered, rows, currencyId, currencyRows)
    }.getOrNull()
}
