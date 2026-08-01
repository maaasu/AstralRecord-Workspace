package io.github.maaasu.astralRecord.feature.status.model

import java.time.LocalDateTime

/**
 * ある時点におけるプレイヤーのステータス一覧です。
 *
 * @property values        ステータス種別ごとの計算結果
 * @property currentHp     現在HP
 * @property currentMp     現在MP
 * @property currentEnergy 現在エネルギー
 * @property calculatedAt  計算日時
 */
data class StatusSnapshot(
    val values: Map<StatusType, StatusValue>,
    val currentHp: Double,
    val currentMp: Double,
    val currentEnergy: Double,
    val currentShield: Double,
    val shieldChangedAtMs: Long,
    val calculatedAt: LocalDateTime,
) {
    /**
     * 指定した種別のステータス値を返します。
     *
     * @param type ステータス種別
     * @return 対応するステータス値。未定義なら null
     */
    fun getValue(type: StatusType): StatusValue? = values[type]

    /**
     * 指定したステータスを参照時に確定します。
     * 範囲値は下限以上・上限以下からランダム取得し、単一値はそのまま返します。
     *
     * @param type 参照するステータス種別
     * @return 確定した値。未定義の場合は0.0
     */
    fun rollValue(type: StatusType): Double = values[type]?.rollValue() ?: 0.0

    /**
     * 指定した種別の最大値（totalValue）を返します。
     *
     * @param type ステータス種別
     * @return 最大値。未定義なら 0.0
     */
    fun getMaxValue(type: StatusType): Double = values[type]?.maxValue ?: 0.0

    /**
     * 現在HP/MP/エネルギーを上限範囲へクランプした新しいスナップショットを返します。
     *
     * @return クランプ後の [StatusSnapshot]
     */
    fun clampCurrentValues(): StatusSnapshot {
        val maxHp = getMaxValue(StatusType.MAX_HEALTH)
        val maxMp = getMaxValue(StatusType.MAX_MANA)
        val maxEnergy = getMaxValue(StatusType.MAX_ENERGY)
        val maxShield = getMaxValue(StatusType.MAX_SHIELD)
        return copy(
            currentHp = currentHp.coerceIn(0.0, maxHp),
            currentMp = currentMp.coerceIn(0.0, maxMp),
            currentEnergy = currentEnergy.coerceIn(0.0, maxEnergy),
            currentShield = currentShield.coerceIn(0.0, maxShield),
        )
    }

    /**
     * 現在HP/MP/エネルギーを更新した新しいスナップショットを返します。
     *
     * @param hp     新しい現在HP
     * @param mp     新しい現在MP
     * @param energy 新しい現在エネルギー（省略時は現在値を維持）
     * @return 更新後（上限クランプ済み）の [StatusSnapshot]
     */
    @JvmOverloads
    fun withCurrentValues(
        hp: Double,
        mp: Double,
        energy: Double = currentEnergy,
        shield: Double = currentShield,
    ): StatusSnapshot {
        val clamped = copy(currentHp = hp, currentMp = mp, currentEnergy = energy, currentShield = shield).clampCurrentValues()
        val shieldChangedAt = if (clamped.currentShield != currentShield) System.currentTimeMillis() else shieldChangedAtMs
        return clamped.copy(shieldChangedAtMs = shieldChangedAt)
    }

    /**
     * 現在シールド値だけを更新した新しいスナップショットを返します。
     *
     * @param shield 新しい現在シールド値
     * @return 上限下限へクランプ済みの [StatusSnapshot]
     */
    fun withCurrentShield(shield: Double): StatusSnapshot {
        return withCurrentValues(currentHp, currentMp, currentEnergy, shield)
    }

    /** スキル個体だけに適用する加算補正を反映した一時スナップショットを返します。 */
    fun withFlatBonuses(bonuses: Map<StatusType, Double>): StatusSnapshot {
        if (bonuses.isEmpty()) return this
        val next = values.toMutableMap()
        for ((type, amount) in bonuses) {
            if (!amount.isFinite()) continue
            val current = next[type] ?: StatusValue(0.0, 0.0)
            next[type] = current.copy(
                bonusMinValue = current.bonusMinValue + amount,
                bonusMaxValue = current.bonusMaxValue + amount,
            )
        }
        return copy(values = next.toMap())
    }

    companion object {
        /**
         * 未初期化状態を表す空スナップショットを返します。
         *
         * @return 空の [StatusSnapshot]
         */
        @JvmStatic
        fun empty(): StatusSnapshot = StatusSnapshot(emptyMap(), 0.0, 0.0, 0.0, 0.0, 0L, LocalDateTime.MIN)
    }
}
