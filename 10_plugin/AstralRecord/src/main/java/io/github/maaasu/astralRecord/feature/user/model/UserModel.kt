package io.github.maaasu.astralRecord.feature.user.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * dbo.user テーブルに対応するデータモデル。
 * プレイヤーの基本情報・BAN状態・選択アカウントを保持します。
 */
data class UserModel(
    val uuid: UUID,
    val mcid: String,
    val joinDate: LocalDateTime,
    val lastJoinDate: LocalDateTime,
    val globalIp: String,
    val accountId: UUID?,
    val banIndefinite: Boolean,
    val banDate: LocalDateTime?,
    val kickIp: Boolean,
    val permission: Int,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val createdBy: UUID,
    val updatedBy: UUID,
    val isDeleted: Boolean,
)

