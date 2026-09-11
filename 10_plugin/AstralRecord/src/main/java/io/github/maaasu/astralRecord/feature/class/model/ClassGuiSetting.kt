package io.github.maaasu.astralRecord.feature.`class`.model

/**
 * クラス選択 GUI における表示設定。
 *
 * @property slot 配置先の Bukkit スロット。未指定時は GUI に表示しない
 */
data class ClassGuiSetting(
    val slot: Int?,
) {
    /**
     * クラス選択 GUI の有効な内容スロットかを判定します。
     *
     * @return 0 以上 53 以下なら true
     */
    fun hasValidSlot(): Boolean = slot != null && slot in 0..53
}
