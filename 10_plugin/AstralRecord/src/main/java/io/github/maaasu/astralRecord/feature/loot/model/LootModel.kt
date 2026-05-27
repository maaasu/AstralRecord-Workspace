package io.github.maaasu.astralRecord.feature.loot.model

/**
 * ルートテーブル定義。
 *
 * @property schemaVersion スキーマバージョン
 * @property id            ルートテーブルID
 * @property name          表示名
 * @property rolls         テーブル全体の抽選回数
 * @property pools         参照解決済みプール一覧
 */
data class LootModel(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val rolls: Int = 1,
    val pools: List<LootPoolModel> = emptyList(),
) {
    /**
     * Lore 表示などで使う平坦化済みエントリ一覧。
     */
    fun flattenedEntries(): List<LootEntry> = pools.flatMap { pool ->
        pool.contents.map { content ->
            LootEntry(
                itemId = content.itemId,
                minAmount = content.minAmount,
                maxAmount = content.maxAmount,
                weight = content.rate,
            )
        }
    }
}

/**
 * ルートプール定義。
 *
 * @property id       ルートプールID
 * @property pick     1回の roll でこのプールから引く回数
 * @property contents 候補一覧
 */
data class LootPoolModel(
    val id: String,
    val pick: Int = 1,
    val contents: List<LootContent> = emptyList(),
)

/**
 * ルートプール内候補。
 *
 * @property itemId    ドロップ対象アイテムID
 * @property minAmount 最小ドロップ数
 * @property maxAmount 最大ドロップ数
 * @property rate      候補重み
 */
data class LootContent(
    val itemId: String,
    val minAmount: Int = 1,
    val maxAmount: Int = 1,
    val rate: Double = 100.0,
)

/**
 * Lore 表示用の簡易エントリ。
 */
data class LootEntry(
    val itemId: String,
    val minAmount: Int = 1,
    val maxAmount: Int = 1,
    val weight: Double = 100.0,
)
