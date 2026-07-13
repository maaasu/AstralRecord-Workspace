package io.github.maaasu.astralRecord.feature.loot.model

/**
 * ルートテーブルの抽選で選択された 1 件の報酬です。
 *
 * @property itemId         アイテム ID
 * @property amount         抽選済み数量
 * @property configuredRate プールに定義されたドロップ率（`rate`、%）
 */
data class LootRollResult(
    val itemId: String,
    val amount: Int,
    val configuredRate: Double,
)
