package io.github.maaasu.astralRecord.feature.item.model

/** API台帳に確定したオーブ装備操作結果。 */
data class EquipmentOrbOperationResult @JvmOverloads constructor(
    val operationId: String,
    val result: EquipmentOrbOperationResultType,
    val operationType: String,
    val equipment: EquipmentInstance?,
    val targetAvailable: Boolean,
    val affectedInventoryEntryIds: List<String>,
    val paymentConsumed: Boolean,
    val enhancementSucceeded: Boolean,
    val failAction: ItemEquipmentEnhanceFailAction?,
    val successRate: Double?,
    val repairedAmount: Int?,
    val transitionName: String?,
    val inventorySnapshot: io.github.maaasu.astralRecord.feature.inventory.model.InventoryOperationSnapshot? = null,
)

/** オーブ装備操作のAPI結果コード。 */
enum class EquipmentOrbOperationResultType {
    APPLIED,
    NO_CANDIDATE,
    NO_SLOT,
    PAYMENT_UNAVAILABLE,
    NOT_ELIGIBLE,
    OPERATION_CONFLICT,
    INVALID,
    ;

    companion object {
        /** API文字列を安全に変換する。 */
        @JvmStatic
        fun fromApiValue(value: String?): EquipmentOrbOperationResultType = runCatching {
            value?.trim()?.uppercase()?.takeIf(String::isNotBlank)?.let(::valueOf)
        }.getOrNull() ?: INVALID
    }
}
