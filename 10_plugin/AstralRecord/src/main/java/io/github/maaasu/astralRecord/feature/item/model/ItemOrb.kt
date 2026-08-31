package io.github.maaasu.astralRecord.feature.item.model

/**
 * オーブ固有定義。
 *
 * 1個のオーブは1種類の装備または習得済みスキル操作だけを持ちます。
 * 強化系は成立した試行開始時に消費し、ルーン操作系は消費しません。
 * シジル操作系は成立時にオーブを1個消費します。
 */
data class ItemOrb(
    val effect: ItemOrbEffect,
)

/** オーブが実行する装備操作。 */
data class ItemOrbEffect(
    val type: ItemOrbEffectType,
    val targetSlots: List<ItemEquipmentSlot> = emptyList(),
    val rank: Int? = null,
    val rankMode: ItemOrbRankMode = ItemOrbRankMode.EXACT,
    val repairAmount: Int? = null,
    val repairFull: Boolean = false,
    val enchantMasterId: String? = null,
    val enchantOperation: ItemOrbEnchantOperation? = null,
)

/** オーブ効果種別。 */
enum class ItemOrbEffectType {
    ENHANCE,
    REPAIR,
    TRANSCENDENCE,
    ENCHANT,
    RUNE_ATTACH,
    RUNE_DETACH,
    SIGIL_ATTACH,
    SIGIL_DETACH,
    ;

    companion object {
        /** API値を安全に列挙値へ変換します。 */
        @JvmStatic
        fun fromApiValue(value: String?): ItemOrbEffectType? = runCatching {
            value?.trim()?.uppercase()?.takeIf(String::isNotBlank)?.let(::valueOf)
        }.getOrNull()
    }
}

/** ランク条件の比較方法。 */
enum class ItemOrbRankMode {
    EXACT,
    AT_MOST,
    ;

    companion object {
        /** API値を安全に列挙値へ変換します。 */
        @JvmStatic
        fun fromApiValue(value: String?): ItemOrbRankMode = runCatching {
            value?.trim()?.uppercase()?.takeIf(String::isNotBlank)?.let(::valueOf)
        }.getOrNull() ?: EXACT
    }
}

/** エンチャントオーブのスロット操作。 */
enum class ItemOrbEnchantOperation {
    OVERWRITE_RANDOM,
    FILL_ONE_EMPTY,
    FILL_ALL_EMPTY,
    ;

    companion object {
        /** API値を安全に列挙値へ変換します。 */
        @JvmStatic
        fun fromApiValue(value: String?): ItemOrbEnchantOperation? = runCatching {
            value?.trim()?.uppercase()?.takeIf(String::isNotBlank)?.let(::valueOf)
        }.getOrNull()
    }
}
