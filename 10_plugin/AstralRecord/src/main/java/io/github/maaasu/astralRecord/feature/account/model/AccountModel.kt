package io.github.maaasu.astralRecord.feature.account.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * アカウントモードを表す列挙型。
 *
 * 番号・英語ID・日本語表示の 3 種で管理する。
 */
enum class AccountMode(val value: Byte, val displayName: String) {
    /** 通常プレイヤー（デフォルト） */
    PLAYER(0, "プレイヤー"),
    /** サーバー管理権限を持つプレイヤー */
    ADMIN(2, "サーバー管理者");

    fun shouldProcessGameplay(): Boolean =
        this == PLAYER

    fun shouldReflectInventoryToGui(): Boolean =
        shouldProcessGameplay()

    companion object {
        /**
         * バイト値から AccountMode を取得します。
         *
         * @param value バイト値
         * @return 対応する AccountMode。一致がない場合は [PLAYER]
         */
        fun fromValue(value: Byte): AccountMode =
            entries.firstOrNull { it.value == value } ?: PLAYER

        /**
         * 名称（大小文字無視）から AccountMode を取得します。
         *
         * @param name 英語ID（`PLAYER` / `ADMIN`）
         * @return 一致するモード。一致がない場合は `null`
         */
        fun fromName(name: String): AccountMode? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

        /**
         * 名称または数値文字列から AccountMode を取得します。
         * 名称優先で解決し、整数として解析できる場合のみ数値で再解決します。
         *
         * @param token 入力トークン
         * @return 一致するモード。一致がない場合は `null`
         */
        fun parse(token: String): AccountMode? {
            fromName(token)?.let { return it }
            val intValue = token.toIntOrNull() ?: return null
            if (intValue < Byte.MIN_VALUE || intValue > Byte.MAX_VALUE) return null
            return entries.firstOrNull { it.value == intValue.toByte() }
        }
    }
}

/**
 * dbo.account テーブルに対応するデータモデル。
 * プレイヤーが所持するゲーム内アカウント（キャラクター）の情報を保持します。
 */
data class AccountModel @JvmOverloads constructor(
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
    val level: Int = 1,
    val totalExperience: Long = 0L,
    val classId: String = "adventurer",
    val classLevel: Int = 1,
    val classExperience: Long = 0L,
    val classProgresses: List<ClassProgressModel> = emptyList(),
)
