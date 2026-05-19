package io.github.maaasu.astralRecord.feature.buff.model

import java.time.LocalDateTime

/**
 * プレイヤーへ付与されているアクティブなバフ状態です。
 *
 * @property type      バフ種別
 * @property startedAt 付与時刻
 * @property expiresAt 失効時刻
 */
data class ActiveBuff(
    val type: BuffType,
    val startedAt: LocalDateTime,
    val expiresAt: LocalDateTime,
) {
    /**
     * バフが失効しているか判定します。
     *
     * @param now 判定時刻
     * @return 失効済みなら true
     */
    fun isExpired(now: LocalDateTime): Boolean = !expiresAt.isAfter(now)
}
