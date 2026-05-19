package io.github.maaasu.astralRecord.feature.account.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * 権限モードを表す列挙型。
 */
enum class AccountMode(val value: Byte) {
    /** 通常プレイヤー（デフォルト） */
    PLAYER(0),
    /** ビルド権限を持つプレイヤー */
    BUILDER(1),
    /** サーバー管理権限を持つプレイヤー */
    ADMIN(2);

    fun shouldReflectInventoryToGui(): Boolean =
        this == PLAYER

    companion object {
        /**
         * バイト値から AccountMode を取得します。
         *
         * @param value バイト値
         * @return 対応する AccountMode
         */
        fun fromValue(value: Byte): AccountMode =
            entries.firstOrNull { it.value == value } ?: PLAYER
    }
}

/**
 * dbo.account テーブルに対応するデータモデル。
 * プレイヤーが所持するゲーム内アカウント（キャラクター）の情報を保持します。
 */
data class AccountModel(
    val uuid: UUID,
    val userId: UUID,
    val accountName: String,
    val slotIndex: Int,
    val isActive: Boolean,
    val mode: AccountMode,
    val menuShortcutsJson: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val createdBy: UUID,
    val updatedBy: UUID,
    val isDeleted: Boolean,
)

