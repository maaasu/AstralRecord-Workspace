package io.github.maaasu.astralRecord.feature.status.model

import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil
import net.kyori.adventure.text.format.NamedTextColor
import java.util.Locale

/**
 * プレイヤーが持つステータスの種別です。
 *
 * 武器には [ATTACK]（攻撃力）のみをステータスとして持たせ、
 * 職業ごとに [STRENGTH]・[DEXTERITY]・[INTELLIGENCE] を参照して
 * 近接攻撃・間接攻撃・魔法攻撃の最終ダメージを内部計算します。
 *
 * レベル・装備・バフ補正を含むすべてのステータス計算は、この列挙を基準に行います。
 *
 * @property displayName 表示用のステータス名
 * @property category    ステータスのカテゴリ
 * @property suffix      表示用の単位サフィックス
 * @property decimalPlaces 表示時の小数桁数
 * @property supportsRange 最小値・最大値による範囲保持を許可するか
 */
enum class StatusType(
    val displayName: String,
    val category: Category,
    private val suffix: String = "",
    private val decimalPlaces: Int = 0,
    val supportsRange: Boolean = true,
) {
    // region リソース系
    /** 最大HP */
    MAX_HEALTH("最大HP", Category.RESOURCE, supportsRange = false),

    /** 最大MP */
    MAX_MANA("最大MP", Category.RESOURCE, supportsRange = false),

    /** 最大エネルギー — スキル発動・ダッシュ・回避行動等に消費するリソース */
    MAX_ENERGY("最大EN", Category.RESOURCE, supportsRange = false),

    /** 最大シールド */
    MAX_SHIELD("最大シールド", Category.RESOURCE, supportsRange = false),
    // endregion

    // region 基本能力値
    /** 筋力 — 近接攻撃のダメージスケーリングに影響 */
    STRENGTH("筋力", Category.PRIMARY),

    /** 器用さ — 間接攻撃（弓・投擲等）のダメージスケーリングに影響 */
    DEXTERITY("器用さ", Category.PRIMARY),

    /** 知力 — 魔法攻撃のダメージスケーリング・最大MP・MP回復に影響 */
    INTELLIGENCE("知力", Category.PRIMARY),

    /** 体力 — 最大HP・防御力・魔法防御力・HP回復に影響 */
    VITALITY("体力", Category.PRIMARY),

    /** 敏捷性 — 攻撃速度・移動速度・回避率に影響 */
    AGILITY("敏捷性", Category.PRIMARY),

    /** 幸運 — 会心率・ドロップ率に影響 */
    LUCK("幸運", Category.PRIMARY),
    // endregion

    // region 攻撃系
    /**
     * 攻撃力 — 武器のベース攻撃力 + バフ等の加算値。
     * 武器が持つ唯一のステータスであり、攻撃種別ごとの最終攻撃力の入力値となります。
     */
    ATTACK("攻撃力", Category.OFFENSE),

    /**
     * 近接攻撃力 — [ATTACK] × [STRENGTH] スケーリングにより算出される近接攻撃の最終攻撃力。
     * 剣・斧・槍など近接武器使用時のダメージ計算に使用します。
     */
    MELEE_ATTACK("近接攻撃力", Category.OFFENSE),

    /**
     * 間接攻撃力 — [ATTACK] × [DEXTERITY] スケーリングにより算出される遠距離攻撃の最終攻撃力。
     * 弓・クロスボウ・投擲武器使用時のダメージ計算に使用します。
     */
    RANGED_ATTACK("間接攻撃力", Category.OFFENSE),

    /**
     * 魔法攻撃力 — [ATTACK] × [INTELLIGENCE] スケーリングにより算出される魔法攻撃の最終攻撃力。
     * 杖・魔導書・オーブ使用時のダメージ計算に使用します。
     */
    MAGIC_ATTACK("魔法攻撃力", Category.OFFENSE),

    /** 会心率 — 攻撃時に会心が発生する確率 */
    CRITICAL_RATE("会心率", Category.OFFENSE, "%", 1),

    /** 会心ダメージ倍率 — 会心発生時のダメージ倍率（150% = 1.5倍） */
    CRITICAL_DAMAGE("会心ダメージ", Category.OFFENSE, "%", 1),

    /**
     * 超会心率 — 会心ヒット時にさらに発動する第二の会心確率。
     * 通常会心が発生した場合にのみ判定され、成功すると [SUPER_CRITICAL_DAMAGE] 倍率が追加適用されます。
     */
    SUPER_CRITICAL_RATE("超会心率", Category.OFFENSE, "%", 1),

    /**
     * 超会心ダメージ倍率 — 超会心発動時に会心ダメージへさらに乗算される倍率。
     * 最終ダメージ = 攻撃力 × (CRITICAL_DAMAGE / 100) × (SUPER_CRITICAL_DAMAGE / 100)
     */
    SUPER_CRITICAL_DAMAGE("超会心ダメージ", Category.OFFENSE, "%", 1),

    /**
     * 最終ダメージ確率 — 全ダメージ計算完了後に追加ダメージが発動する確率。
     * 発動時は [FINAL_DAMAGE_MULTIPLIER] の倍率が最終ダメージに乗算されます。
     */
    FINAL_DAMAGE_RATE("最終ダメージ確率", Category.OFFENSE, "%", 1),

    /**
     * 最終ダメージ倍率 — [FINAL_DAMAGE_RATE] による追加ダメージ発動時の倍率。
     * 130% = 1.3倍のダメージとなります。
     */
    FINAL_DAMAGE_MULTIPLIER("最終ダメージ倍率", Category.OFFENSE, "%", 1),

    /** 命中率 — 攻撃がヒットする確率。[EVASION] との対抗判定 */
    ACCURACY("命中率", Category.OFFENSE, "%", 1),

    /** 攻撃速度 — 攻撃のクールダウン短縮割合。100% が標準速度 */
    ATTACK_SPEED("攻撃速度", Category.OFFENSE, "%", 0),

    /** シールドブレイク — シールドダメージに加算する値 */
    SHIELD_BREAK("シールドブレイク", Category.OFFENSE),
    // endregion

    // region 属性系
    /** 火属性ダメージ増加率 */
    FIRE_DAMAGE_INCREASE("火属性ダメージ増加", Category.ELEMENT, "%", 1),
    /** 火属性耐性 */
    FIRE_RESISTANCE("火属性耐性", Category.ELEMENT, "%", 1),
    /** 火属性貫通 */
    FIRE_PENETRATION("火属性貫通", Category.ELEMENT, "%", 1),
    /** 氷属性ダメージ増加率 */
    ICE_DAMAGE_INCREASE("氷属性ダメージ増加", Category.ELEMENT, "%", 1),
    /** 氷属性耐性 */
    ICE_RESISTANCE("氷属性耐性", Category.ELEMENT, "%", 1),
    /** 氷属性貫通 */
    ICE_PENETRATION("氷属性貫通", Category.ELEMENT, "%", 1),
    /** 雷属性ダメージ増加率 */
    LIGHTNING_DAMAGE_INCREASE("雷属性ダメージ増加", Category.ELEMENT, "%", 1),
    /** 雷属性耐性 */
    LIGHTNING_RESISTANCE("雷属性耐性", Category.ELEMENT, "%", 1),
    /** 雷属性貫通 */
    LIGHTNING_PENETRATION("雷属性貫通", Category.ELEMENT, "%", 1),
    /** 毒属性ダメージ増加率 */
    POISON_DAMAGE_INCREASE("毒属性ダメージ増加", Category.ELEMENT, "%", 1),
    /** 毒属性耐性 */
    POISON_RESISTANCE("毒属性耐性", Category.ELEMENT, "%", 1),
    /** 毒属性貫通 */
    POISON_PENETRATION("毒属性貫通", Category.ELEMENT, "%", 1),
    /** 光属性ダメージ増加率 */
    LIGHT_DAMAGE_INCREASE("光属性ダメージ増加", Category.ELEMENT, "%", 1),
    /** 光属性耐性 */
    LIGHT_RESISTANCE("光属性耐性", Category.ELEMENT, "%", 1),
    /** 光属性貫通 */
    LIGHT_PENETRATION("光属性貫通", Category.ELEMENT, "%", 1),
    /** 闇属性ダメージ増加率 */
    DARK_DAMAGE_INCREASE("闇属性ダメージ増加", Category.ELEMENT, "%", 1),
    /** 闇属性耐性 */
    DARK_RESISTANCE("闇属性耐性", Category.ELEMENT, "%", 1),
    /** 闇属性貫通 */
    DARK_PENETRATION("闇属性貫通", Category.ELEMENT, "%", 1),
    // endregion

    // region 状態異常系
    BURNING_APPLY_CHANCE("燃焼付与確率増加", Category.CONDITION, "%", 1),
    BURNING_RESISTANCE("燃焼付与耐性", Category.CONDITION, "%", 1),
    BURNING_DAMAGE_INCREASE("燃焼ダメージ増加", Category.CONDITION, "%", 1),
    BURNING_DAMAGE_RESISTANCE("燃焼ダメージ耐性", Category.CONDITION, "%", 1),
    BURNING_DAMAGE_PENETRATION("燃焼DoT貫通", Category.CONDITION, "%", 1),
    FROZEN_APPLY_CHANCE("凍結付与確率増加", Category.CONDITION, "%", 1),
    FROZEN_RESISTANCE("凍結付与耐性", Category.CONDITION, "%", 1),
    CHILLED_APPLY_CHANCE("冷気付与確率増加", Category.CONDITION, "%", 1),
    CHILLED_RESISTANCE("冷気付与耐性", Category.CONDITION, "%", 1),
    SHOCKED_APPLY_CHANCE("感電付与確率増加", Category.CONDITION, "%", 1),
    SHOCKED_RESISTANCE("感電付与耐性", Category.CONDITION, "%", 1),
    SHOCKED_DAMAGE_INCREASE("感電ダメージ増加", Category.CONDITION, "%", 1),
    SHOCKED_DAMAGE_RESISTANCE("感電ダメージ耐性", Category.CONDITION, "%", 1),
    SHOCKED_DAMAGE_PENETRATION("感電DoT貫通", Category.CONDITION, "%", 1),
    POISONED_APPLY_CHANCE("毒状態付与確率増加", Category.CONDITION, "%", 1),
    POISONED_RESISTANCE("毒状態付与耐性", Category.CONDITION, "%", 1),
    POISONED_DAMAGE_INCREASE("毒状態ダメージ増加", Category.CONDITION, "%", 1),
    POISONED_DAMAGE_RESISTANCE("毒状態ダメージ耐性", Category.CONDITION, "%", 1),
    POISONED_DAMAGE_PENETRATION("毒状態DoT貫通", Category.CONDITION, "%", 1),
    BLINDNESS_APPLY_CHANCE("盲目付与確率増加", Category.CONDITION, "%", 1),
    BLINDNESS_RESISTANCE("盲目付与耐性", Category.CONDITION, "%", 1),
    WEAKNESS_APPLY_CHANCE("衰弱付与確率増加", Category.CONDITION, "%", 1),
    WEAKNESS_RESISTANCE("衰弱付与耐性", Category.CONDITION, "%", 1),
    HEALING_INHIBITION_APPLY_CHANCE("回復阻害付与確率増加", Category.CONDITION, "%", 1),
    HEALING_INHIBITION_RESISTANCE("回復阻害付与耐性", Category.CONDITION, "%", 1),
    // endregion

    // region 防御系
    /** 防御力 — 近接・間接攻撃によるダメージを軽減 */
    DEFENSE("防御力", Category.DEFENSE),

    /** 魔法防御力 — 魔法攻撃によるダメージを軽減 */
    MAGIC_DEFENSE("魔法防御力", Category.DEFENSE),

    /** 回避率 - 攻撃を完全に回避する確率 */
    EVASION("回避率", Category.DEFENSE, "%", 1),

    /** ノックバック耐性 - 受けるノックバック量を割合で軽減する */
    KNOCKBACK_RESISTANCE("ノックバック耐性", Category.DEFENSE, "%", 1),
    // endregion

    // region 回復・ユーティリティ系
    /** HP自然回復量（5秒あたり） */
    HP_REGEN("HP回復力", Category.UTILITY),

    /** MP自然回復量（5秒あたり） */
    MP_REGEN("MP回復力", Category.UTILITY),

    /** エネルギー自然回復量（5秒あたり） */
    ENERGY_REGEN("EN回復力", Category.UTILITY),

    /** 移動速度 — 100 がバニラ標準速度 */
    MOVEMENT_SPEED("移動速度", Category.UTILITY),

    /** クールダウン短縮率 */
    COOLDOWN_REDUCTION("CD短縮", Category.UTILITY, "%", 1),

    /** シールドリチャージ短縮率 */
    SHIELD_RECHARGE_REDUCTION("シールドリチャージ短縮", Category.UTILITY, "%", 1),

    /** シールドリチャージレート */
    SHIELD_RECHARGE_RATE("シールドリチャージ", Category.UTILITY),

    /** 採集速度 — 採集オブジェクトへ1回の採集判定で与える破壊力 */
    MINING_SPEED("採集速度", Category.UTILITY),

    /** クエストを同時に受領できる最大数 */
    QUEST_LIMIT("クエスト受領上限", Category.UTILITY, supportsRange = false),
    ;

    /**
     * ステータスのカテゴリです。
     *
     * @property displayName 表示用のカテゴリ名
     */
    enum class Category(val displayName: String) {
        /** リソース系（HP/MP/EN） */
        RESOURCE("リソース"),

        /** 基本能力値（STR/DEX/INT/VIT/AGI/LUK） */
        PRIMARY("基本能力値"),

        /** 攻撃系（ATK/攻撃種別/CRI/超会心/最終ダメージ 等） */
        OFFENSE("攻撃"),

        /** 防御系（DEF/MDEF/EVA） */
        DEFENSE("防御"),

        /** 属性ダメージ・耐性・貫通 */
        ELEMENT("属性"),

        /** 状態異常付与・耐性・DoT補正 */
        CONDITION("状態異常"),

        /** 回復・ユーティリティ系 */
        UTILITY("ユーティリティ"),
    }

    /**
     * ステータス値を表示用文字列に変換します。
     *
     * @param value 表示対象の値
     * @return suffix を含む表示用文字列
     */
    fun formatValue(value: Double): String {
        val pattern = "% ,.${decimalPlaces}f"
            .replace(" ", "")
        return String.format(Locale.US, pattern, value) + suffix
    }

    /** `%` 単位で表示するステータスかどうかを返します。 */
    fun isPercentage(): Boolean = suffix == "%"

    /**
     * 符号付きのステータス補正値を表示用文字列に変換します。
     *
     * @param value 表示対象の補正値
     * @return 正の値には `+` を付与した表示用文字列
     */
    fun formatSignedValue(value: Double): String {
        val sign = if (value > 0.0) "+" else ""
        return sign + formatValue(value)
    }

    /**
     * 最小値・最大値を表示用文字列へ変換します。
     * 同値の場合は単一値として表示します。
     *
     * @param minValue 表示する下限
     * @param maxValue 表示する上限
     * @return 単一値または `下限 ～ 上限` 形式の文字列
     */
    fun formatRange(minValue: Double, maxValue: Double): String =
        if (minValue == maxValue) formatValue(minValue) else "${formatValue(minValue)} ～ ${formatValue(maxValue)}"

    /**
     * 最小補正値・最大補正値を符号付き表示用文字列へ変換します。
     * 同値の場合は単一値として表示します。
     *
     * @param minValue 表示する補正下限
     * @param maxValue 表示する補正上限
     * @return 符号付きの単一値または範囲文字列
     */
    fun formatSignedRange(minValue: Double, maxValue: Double): String =
        if (minValue == maxValue) formatSignedValue(minValue)
        else "${formatSignedValue(minValue)} ～ ${formatSignedValue(maxValue)}"

    /**
     * ステータス名の共通表示色を返します。
     *
     * @return UI 表示で使う Adventure 色
     */
    fun namedColor(): NamedTextColor =
        when (category) {
            Category.RESOURCE -> NamedTextColor.GOLD
            Category.PRIMARY -> NamedTextColor.GREEN
            Category.OFFENSE -> NamedTextColor.RED
            Category.DEFENSE -> NamedTextColor.BLUE
            Category.ELEMENT -> NamedTextColor.LIGHT_PURPLE
            Category.CONDITION -> NamedTextColor.DARK_PURPLE
            Category.UTILITY -> NamedTextColor.YELLOW
        }

    /**
     * ステータス名の共通表示色を legacy color code で返します。
     *
     * @return legacy color code
     */
    fun legacyColor(): String =
        when (category) {
            Category.RESOURCE -> ColorCodeUtil.GOLD
            Category.PRIMARY -> ColorCodeUtil.GREEN
            Category.OFFENSE -> ColorCodeUtil.RED
            Category.DEFENSE -> ColorCodeUtil.BLUE
            Category.ELEMENT -> ColorCodeUtil.LIGHT_PURPLE
            Category.CONDITION -> ColorCodeUtil.DARK_PURPLE
            Category.UTILITY -> ColorCodeUtil.YELLOW
        }

    companion object {
        /**
         * 指定カテゴリに属するステータス種別の一覧を返します。
         *
         * @param category 対象カテゴリ
         * @return カテゴリに属する [StatusType] のリスト
         */
        @JvmStatic
        fun byCategory(category: Category): List<StatusType> =
            entries.filter { it.category == category }
    }
}
