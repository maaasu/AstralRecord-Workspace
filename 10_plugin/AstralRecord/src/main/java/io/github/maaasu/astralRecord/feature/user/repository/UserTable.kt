package io.github.maaasu.astralRecord.feature.user.repository

/**
 * dbo.user テーブルのカラム名定数。
 */
internal object UserTable {
    const val TABLE_NAME    = "[dbo].[user]"
    const val UUID          = "uuid"
    const val MCID          = "mcid"
    const val JOIN_DATE     = "join_date"
    const val LAST_JOIN_DATE = "last_join_date"
    const val GLOBAL_IP     = "global_ip"
    const val ACCOUNT_ID    = "account_id"
    const val BAN_INDEFINITE = "ban_indefinite"
    const val BAN_DATE      = "ban_date"
    const val KICK_IP       = "kick_ip"
    const val PERMISSION    = "permission"
    const val CREATED_AT    = "created_at"
    const val UPDATED_AT    = "updated_at"
    const val CREATED_BY    = "created_by"
    const val UPDATED_BY    = "updated_by"
    const val IS_DELETED    = "is_deleted"
}
