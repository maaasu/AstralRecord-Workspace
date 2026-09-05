package io.github.maaasu.astralRecord.feature.inventory.model

import java.util.UUID

/** API操作応答時の正本。covered内に存在しないentryは削除または所有外を表す。 */
data class InventoryOperationSnapshot(
    val accountId: UUID,
    val coveredEntryIds: Set<UUID>,
    val entries: List<InventoryEntryModel>,
    val currencyInventoryId: UUID?,
    val currencyEntries: List<InventoryEntryModel>,
) {
    /**
     * 指定accountと全対象IDを含む場合だけ再取得を省略できる。通信・状態変更は行わない。
     * @param account 照合対象の所有者
     * @param ids 照合対象の全entry ID。空集合も許容する
     * @return 所有者が一致し全IDを収録している場合true
     */
    fun covers(account: UUID, ids: Collection<UUID>): Boolean =
        accountId == account && coveredEntryIds.containsAll(ids)
}
