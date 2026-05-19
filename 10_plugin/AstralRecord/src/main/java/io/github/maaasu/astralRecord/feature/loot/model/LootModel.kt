package io.github.maaasu.astralRecord.feature.loot.model

/**
 * ルートテーブル定義。
 *
 * @property schemaVersion スキーマバージョン
 * @property id            ルートテーブルID
 * @property name          表示名
 * @property entries       ドロップエントリ一覧
 */
data class LootModel(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val entries: List<LootEntry> = emptyList(),
)

/**
 * ルートテーブルの個別エントリ。
 *
 * @property category  ドロップアイテムのカテゴリ
 * @property itemId    ドロップアイテムID
 * @property minAmount 最小ドロップ数
 * @property maxAmount 最大ドロップ数
 * @property weight    ドロップ確率（0.0 ～ 100.0）
 */
data class LootEntry(
    val category: String,
    val itemId: String,
    val minAmount: Int = 1,
    val maxAmount: Int = 1,
    val weight: Double = 100.0,
)

