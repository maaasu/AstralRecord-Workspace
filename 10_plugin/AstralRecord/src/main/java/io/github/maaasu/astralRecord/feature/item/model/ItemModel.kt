package io.github.maaasu.astralRecord.feature.item.model

/**
 * アイテム定義。
 */
data class ItemModel(
    val schemaVersion: Int,
    val id: String,
    val category: String,
    val name: String,
    val icon: String,
    val rarity: String,
    val maxStack: Int = 64,
    val saleValue: Int,
    val customModelData: Int?,
    val appearance: ItemAppearance?,
    val lore: List<String> = emptyList(),
    val unTradeable: Boolean,
    val unSellable: Boolean,
    val bundle: ItemBundle?,
    val currency: ItemCurrency?,
    val equipment: ItemEquipment?,
    val rune: ItemRune?,
    val consumable: ItemConsumable?,
    val skillGem: ItemSkillGem? = null,
    val sigil: ItemSigil? = null,
)
