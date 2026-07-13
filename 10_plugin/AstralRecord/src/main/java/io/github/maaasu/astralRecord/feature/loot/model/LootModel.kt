package io.github.maaasu.astralRecord.feature.loot.model

/**
 * ルートテーブル定義。
 *
 * @property schemaVersion スキーマバージョン
 * @property id            ルートテーブルID
 * @property name          表示名
 * @property minRolls      テーブル全体の最小抽選回数
 * @property maxRolls      テーブル全体の最大抽選回数
 * @property pools         参照解決済みプール一覧
 */
data class LootModel(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val minRolls: Int = 1,
    val maxRolls: Int = minRolls,
    val pools: List<LootPoolModel> = emptyList(),
) {
    /** 固定回数の旧形式からモデルを構築します。 */
    constructor(
        schemaVersion: Int,
        id: String,
        name: String,
        rolls: Int,
        pools: List<LootPoolModel>,
    ) : this(schemaVersion, id, name, rolls, rolls, pools)

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
 * @property minPick  1回の roll で採用する最小件数
 * @property maxPick  1回の roll で採用する最大件数
 * @property contents 候補一覧
 */
data class LootPoolModel(
    val id: String,
    val minPick: Int,
    val maxPick: Int,
    val contents: List<LootContent> = emptyList(),
) {
    /** 固定上限の旧形式からモデルを構築します。 */
    constructor(
        id: String,
        pick: Int,
        contents: List<LootContent>,
    ) : this(id, pick, pick, contents)

    /** pick 未指定時の既定値でモデルを構築します。 */
    constructor(
        id: String,
        contents: List<LootContent>,
    ) : this(id, contents.size, contents.size, contents)
}

/**
 * ルートプール内候補。
 *
 * @property itemId    ドロップ対象アイテムID
 * @property minAmount 最小ドロップ数
 * @property maxAmount 最大ドロップ数
 * @property rate      独立ドロップ率（%）
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
