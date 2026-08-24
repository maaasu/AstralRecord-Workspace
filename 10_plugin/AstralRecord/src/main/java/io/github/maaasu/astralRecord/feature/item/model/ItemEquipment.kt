package io.github.maaasu.astralRecord.feature.item.model

import java.math.BigDecimal
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.nextUp

/**
 * equipment カテゴリ拡張定義。
 */
data class ItemEquipment(
    val slot: ItemEquipmentSlot?,
    val handType: ItemEquipmentHandType = ItemEquipmentHandType.ONE,
    /** 採集ツール種別など equipment master が定義する用途タグ。 */
    val tag: String? = null,
    val requiredLevel: Int = 0,
    /** 装備可能な現在クラスと必要クラスレベルの組み合わせ。空ならクラス制限なし。 */
    val requiredClasses: List<ItemEquipmentClassRequirement> = emptyList(),
    val setId: String? = null,
    val stats: List<ItemEquipmentStat> = emptyList(),
    val durability: ItemEquipmentDurability?,
    /** 強化システム定義（null ならば強化不可） */
    val enhance: ItemEquipmentEnhance? = null,
    /** エンチャントシステム定義（null ならばエンチャント不可） */
    val enchant: ItemEquipmentEnchantDef? = null,
    /** ルーンスロット定義（null ならばルーン装着不可） */
    val rune: ItemEquipmentRuneDef? = null,
    /** 状態変化定義リスト */
    val transcendence: List<ItemEquipmentTranscendence> = emptyList(),
)

enum class ItemEquipmentSlot(val displayName: String) {
    WEAPON("武器"),
    SUBWEAPON("補助装備"),
    HEAD("頭"),
    CHEST("胴"),
    LEGS("脚"),
    FEET("足"),
    ACCESSORY("アクセサリ"),
    TOOL("道具"),
    UNKNOWN("不明"),
    ;

    companion object {
        @JvmStatic
        fun fromApiValue(value: String?): ItemEquipmentSlot {
            if (value.isNullOrBlank()) {
                return UNKNOWN
            }
            return try {
                valueOf(value.trim().uppercase())
            } catch (_: IllegalArgumentException) {
                UNKNOWN
            }
        }
    }
}

enum class ItemEquipmentHandType(val displayName: String) {
    ONE("片手"),
    TWO("両手"),
    ;

    companion object {
        @JvmStatic
        fun fromApiValue(value: String?): ItemEquipmentHandType {
            if (value.isNullOrBlank()) {
                return ONE
            }
            return try {
                valueOf(value.trim().uppercase())
            } catch (_: IllegalArgumentException) {
                ONE
            }
        }
    }
}

enum class ItemEquipmentStatType {
    FLAT,
    SCALAR,
    ;

    companion object {
        @JvmStatic
        fun fromApiValue(value: String?): ItemEquipmentStatType {
            if (value.isNullOrBlank()) {
                return FLAT
            }
            return try {
                valueOf(value.trim().uppercase())
            } catch (_: IllegalArgumentException) {
                FLAT
            }
        }
    }
}

data class ItemEquipmentStat @JvmOverloads constructor(
    val status: String,
    val type: ItemEquipmentStatType,
    val min: Double,
    val max: Double,
    /** マスタで指定された min の生値。表示用の乱数範囲を保持します。 */
    val rawMin: String? = null,
    /** マスタで指定された max の生値。表示用の乱数範囲を保持します。 */
    val rawMax: String? = null,
) {
    /** 値が範囲指定かどうかを返します。 */
    val isRange: Boolean
        get() = min != max

    /**
     * 参照時に使用するステータス値を返します。
     * min/max が同値の場合は固定値、異なる場合は範囲内のランダム値を返します。
     */
    fun rollValue(): Double {
        if (!isRange) {
            return min
        }
        return ThreadLocalRandom.current().nextDouble(min, max.nextUp())
    }

    /**
     * 表示向けの値文字列を返します。
     * 固定値は単一値、範囲指定は min~max 形式で返します。
     */
    fun displayValue(): String {
        if (!isRange) {
            return formatNumber(min)
        }
        return "${formatNumber(min)}~${formatNumber(max)}"
    }

    private fun formatNumber(value: Double): String {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
}

data class ItemEquipmentDurability(
    val max: Int,
    val consume: Int = 1,
)

/**
 * 装備に必要な現在クラスとクラスレベル。
 */
data class ItemEquipmentClassRequirement(
    /** クラス ID */
    val classId: String,
    /** 必要クラスレベル */
    val level: Int = 1,
)

/**
 * 強化システム定義。
 */
data class ItemEquipmentEnhance(
    /** 強化最大レベル */
    val maxLevel: Int,
    /** 各強化レベルの定義 */
    val levels: List<ItemEquipmentEnhanceLevel> = emptyList(),
)

/**
 * 強化レベル定義。
 */
data class ItemEquipmentEnhanceLevel(
    /** 強化レベル（1〜maxLevel） */
    val level: Int,
    /** このレベルで前レベルからの差分として加算されるステータス */
    val statIncrease: List<ItemEquipmentEnhanceStatIncrease> = emptyList(),
    /** このレベルで加算される最大耐久値 */
    val durabilityBonus: Int?,
    val successRate: Double = 1.0,
    val failAction: ItemEquipmentEnhanceFailAction = ItemEquipmentEnhanceFailAction.NONE,
    /** SET_LEVEL 失敗時に設定する強化値。 */
    val failTargetLevel: Int? = null,
)

/**
 * 強化レベル1段階分のステータス増加定義。
 * 前レベルからの差分値（累積はプラグイン側で計算）。
 */
data class ItemEquipmentEnhanceStatIncrease(
    val status: String,
    val type: ItemEquipmentStatType,
    /** 増加幅の下限 */
    val min: Double,
    /** 増加幅の上限（固定値の場合は min と同値） */
    val max: Double,
)

data class ItemEquipmentEnhanceMaterial(
    val itemId: String,
    val amount: Int = 1,
)

enum class ItemEquipmentEnhanceFailAction {
    NONE,
    SET_LEVEL,
    DECREASE_ONE,
    ;

    companion object {
        @JvmStatic
        fun fromApiValue(value: String?): ItemEquipmentEnhanceFailAction {
            if (value.isNullOrBlank()) {
                return NONE
            }
            return try {
                valueOf(value.trim().uppercase())
            } catch (_: IllegalArgumentException) {
                NONE
            }
        }
    }
}

/**
 * エンチャントシステム定義。
 */
data class ItemEquipmentEnchantDef(
    /** エンチャント最大スロット数 */
    val maxSlots: Int,
)

/**
 * ルーンスロットシステム定義。
 */
data class ItemEquipmentRuneDef(
    /** 最大スロット数（固定値 or "1~3" 形式のランダム表現） */
    val maxSlotsRaw: String,
    /** 装着を許可するルーン ID リスト（空なら全許可） */
    val allowedRuneIds: List<String> = emptyList(),
)

/**
 * 状態変化（進化・覚醒・超越など）定義。
 */
data class ItemEquipmentTranscendence(
    /** 状態変化の名称 */
    val name: String?,
    /** 状態変化の強さ指標 */
    val rank: Int,
    /** 状態変化に必要な強化レベル */
    val requiredEnhanceLevel: Int = 0,
    /** 状態変化に必要な素材 */
    val requiredMaterials: List<ItemEquipmentEnhanceMaterial> = emptyList(),
    /** 状態変化に必要な通貨量 */
    val requiredCurrency: Int = 0,
    /** 状態変化後のアイテム名称（未設定なら null） */
    val overridesName: String?,
    /** 状態変化後の強化最大レベル（未設定なら null） */
    val overridesEnhanceMaxLevel: Int?,
    /** 状態変化後のエンチャント最大スロット数（未設定なら null） */
    val overridesEnchantMaxSlots: Int?,
)
