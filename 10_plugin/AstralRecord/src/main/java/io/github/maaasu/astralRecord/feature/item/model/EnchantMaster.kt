package io.github.maaasu.astralRecord.feature.item.model

/** 共通エンチャントマスタ。 */
data class EnchantMaster(
    val schemaVersion: Int,
    val id: String,
    val targets: List<EnchantTarget>,
)

/** 装備グループごとの重み付き候補。 */
data class EnchantTarget(
    val equipmentType: EnchantEquipmentType,
    val entries: List<EnchantEntry>,
)

/** 1つのエンチャント効果候補。 */
data class EnchantEntry(
    val effectId: String,
    val status: String,
    val type: String,
    val value: String,
    val weight: Int,
)

/** 共通エンチャントマスタが区別する装備グループ。 */
enum class EnchantEquipmentType {
    WEAPON,
    ARMOR,
    ACCESSORY,
    ;
}
