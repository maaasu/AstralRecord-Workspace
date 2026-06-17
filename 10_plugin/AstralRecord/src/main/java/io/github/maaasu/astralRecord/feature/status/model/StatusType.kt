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
 */
enum class StatusType(
    val displayName: String,
    val category: Category,
    private val suffix: String = "",
    private val decimalPlaces: Int = 0,
) {
    // region リソース系
    /** 最大HP */
    MAX_HEALTH("最大HP", Category.RESOURCE),

    /** 最大MP */
    MAX_MANA("最大MP", Category.RESOURCE),

    /** 最大エネルギー — スキル発動・ダッシュ・回避行動等に消費するリソース */
    MAX_ENERGY("最大EN", Category.RESOURCE),

    /** 最大シールド */
    MAX_SHIELD("最大シールド", Category.RESOURCE),
    // endregion

    // region 基本能力値
    /** 筋力 — 近接攻撃のダメージスケーリングに影響 */
    STRENGTH("筋力", Category.PRIMARY),

    /** 器用さ — 間接攻撃（弓・投擲等）のダメージスケーリングに影響 */
    DEXTERITY("器用さ", Category.PRIMARY),

    /** 知力 — 魔法攻撃のダメージスケーリング・最大MP・MP回復に影響 */
    INTELLIGENCE("知力", Category.PRIMARY),

    /** 体力 — 最大HP・物理/魔法防御・HP回復に影響 */
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

    // region 防御系
    /** 物理防御力 — 近接・間接攻撃による物理ダメージを軽減 */
    DEFENSE("物理防御力", Category.DEFENSE),

    /** 魔法防御力 — 魔法攻撃によるダメージを軽減 */
    MAGIC_DEFENSE("魔法防御力", Category.DEFENSE),

    /** 回避率 — 攻撃を完全に回避する確率 */
    EVASION("回避率", Category.DEFENSE, "%", 1),
    // endregion

    // region 回復・ユーティリティ系
    /** HP自然回復量（5秒あたり） */
    HP_REGEN("HP回復力", Category.UTILITY),

    /** MP自然回復量（5秒あたり） */
    MP_REGEN("MP回復力", Category.UTILITY),

    /** エネルギー自然回復量（5秒あたり） */
    ENERGY_REGEN("EN回復力", Category.UTILITY),

    /** 移動速度 — 100% が標準速度 */
    MOVEMENT_SPEED("移動速度", Category.UTILITY, "%", 0),

    /** クールダウン短縮率 */
    COOLDOWN_REDUCTION("CD短縮", Category.UTILITY, "%", 1),

    /** シールドリチャージ短縮率 */
    SHIELD_RECHARGE_REDUCTION("シールドリチャージ短縮", Category.UTILITY, "%", 1),

    /** シールドリチャージレート */
    SHIELD_RECHARGE_RATE("シールドリチャージ", Category.UTILITY),
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
