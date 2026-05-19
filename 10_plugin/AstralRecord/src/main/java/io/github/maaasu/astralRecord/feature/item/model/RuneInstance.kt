package io.github.maaasu.astralRecord.feature.item.model

/**
 * ルーンインスタンス。
 * rune API（`/api/rune/instances`）から取得するルーンの個別動的データ。
 * アイテムマスタ（ItemModel）とは異なり、DB に保存されたプレイヤー固有のルーン情報を表します。
 */
data class RuneInstance(
    /** ルーンインスタンス ID（UUID） */
    val runeInstanceId: String,
    /** 所有アカウント ID（UUID） */
    val accountId: String,
    /** アイテムテンプレート ID */
    val itemId: String,
    /** 作成日時（ISO 8601） */
    val createdAt: String,
    /** 更新日時（ISO 8601） */
    val updatedAt: String,
    /** ステータスロール一覧 */
    val statRolls: List<RuneStatRoll> = emptyList(),
)

/**
 * ルーンインスタンスのステータスロール。
 * 作成時に確定した実値（value）を保持します。
 * equipment の [EquipmentStatRoll]（min/max 範囲）とは異なり、単一の確定値です。
 */
data class RuneStatRoll(
    /** ステータスロール ID（UUID） */
    val statRollId: String,
    /** ステータス種別 */
    val status: String,
    /** 補正方式 */
    val type: String,
    /** 確定した実値 */
    val value: String,
    /** 表示順序 */
    val sortOrder: Int,
)

