package io.github.maaasu.astralRecord.feature.item.model

/**
 * 装備インスタンス。
 * equipment API（`/api/equipment/instances`）から取得する装備の個別動的データ。
 * アイテムマスタ（ItemModel）とは異なり、DB に保存されたプレイヤー固有の装備情報を表します。
 */
data class EquipmentInstance(
    /** 装備インスタンス ID（UUID） */
    val equipmentInstanceId: String,
    /** 所有アカウント ID（UUID） */
    val accountId: String,
    /** アイテムテンプレート ID */
    val itemId: String,
    /** 強化レベル */
    val enhanceLevel: Int,
    /** ルーンスロット最大数 */
    val runeMaxSlots: Int,
    /** 超越ランク */
    val transcendenceRank: Int,
    /** 耐久値上限 */
    val durabilityMax: Int,
    /** 耐久値現在値 */
    val durabilityValue: Int,
    /** 作成日時（ISO 8601） */
    val createdAt: String,
    /** 更新日時（ISO 8601） */
    val updatedAt: String,
    /** ステータスロール一覧 */
    val statRolls: List<EquipmentStatRoll> = emptyList(),
    /** エンチャント一覧 */
    val enchants: List<EquipmentEnchant> = emptyList(),
    /** ルーン一覧 */
    val runes: List<EquipmentRune> = emptyList(),
    /** エンチャントプール一覧 */
    val enchantPools: List<EquipmentEnchantPool> = emptyList(),
)

/**
 * 装備インスタンスのステータスロール。
 * 作成時に確定した乱数範囲（min/max）を保持します。
 */
data class EquipmentStatRoll(
    /** ステータスロール ID（UUID） */
    val statRollId: String,
    /** ステータス種別 */
    val status: String,
    /** 確定した乱数下限値 */
    val min: String,
    /** 確定した乱数上限値 */
    val max: String,
    /** 表示順序 */
    val sortOrder: Int,
)

/**
 * 装備インスタンスに付与されたエンチャント。
 */
data class EquipmentEnchant(
    /** エンチャントレコード ID（UUID） */
    val enchantId: String,
    /** 対象装備インスタンス ID（UUID） */
    val equipmentInstanceId: String,
    /** 使用しているスロット番号 */
    val slotIndex: Int,
    /** 付与元エンチャントプールのインデックス */
    val poolIndex: Int,
    /** 付与ステータス */
    val status: String,
    /** 補正方式 */
    val type: String,
    /** 確定した付与値 */
    val value: Double,
)

/**
 * 装備インスタンスに装着されたルーン。
 */
data class EquipmentRune(
    /** ルーン装着レコード ID（UUID） */
    val runeId: String,
    /** 対象装備インスタンス ID（UUID） */
    val equipmentInstanceId: String,
    /** ルーンスロット番号 */
    val slotIndex: Int,
    /** 装着中のルーンアイテム ID */
    val itemId: String,
)

/**
 * 装備インスタンスのエンチャントプール構成。
 */
data class EquipmentEnchantPool(
    /** プールインデックス */
    val poolIndex: Int,
    /** レシピ ID（nullable） */
    val recipeId: String?,
    /** 必要素材アイテム ID */
    val requiredMaterialItemId: String,
    /** 必要素材数量 */
    val requiredMaterialAmount: Int,
    /** 必要通貨量 */
    val requiredCurrency: Int,
)

