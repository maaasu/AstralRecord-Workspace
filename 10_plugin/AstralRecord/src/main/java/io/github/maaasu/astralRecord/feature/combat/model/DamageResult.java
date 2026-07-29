package io.github.maaasu.astralRecord.feature.combat.model;

/**
 * ダメージ計算結果を表します。
 *
 * @param finalDamage  HP に適用する最終ダメージ
 * @param shieldDamage シールドに適用するダメージ
 * @param shieldBroken この結果でシールドを破壊したか
 * @param critical     通常会心が成立したか
 * @param superStarCritical 超星会心が成立したか
 * @param evaded       命中判定で回避されたか
 * @param hitChance    命中判定に使用した最終命中率（%）
 * @param accuracy     攻撃者の命中率（%）
 * @param evasion      被弾者の回避率（%）
 * @param breakdown    計算時点の攻撃力・防御力・属性耐性
 */
public record DamageResult(
        double finalDamage,
        double shieldDamage,
        boolean shieldBroken,
        boolean critical,
        boolean superStarCritical,
        boolean evaded,
        double hitChance,
        double accuracy,
        double evasion,
        DamageBreakdown breakdown
) {

    public DamageResult {
        breakdown = breakdown == null ? DamageBreakdown.empty() : breakdown;
    }

    /**
     * 中間計算値を持たない従来形式の結果を作成します。
     *
     * @param finalDamage  HP に適用する最終ダメージ
     * @param shieldDamage シールドに適用するダメージ
     * @param shieldBroken この結果でシールドを破壊したか
     * @param critical     通常会心が成立したか
     * @param evaded       命中判定で回避されたか
     * @param hitChance    最終命中率
     * @param accuracy     攻撃者の命中率
     * @param evasion      被弾者の回避率
     */
    public DamageResult(
            double finalDamage,
            double shieldDamage,
            boolean shieldBroken,
            boolean critical,
            boolean evaded,
            double hitChance,
            double accuracy,
            double evasion
    ) {
        this(
                finalDamage,
                shieldDamage,
                shieldBroken,
                critical,
                false,
                evaded,
                hitChance,
                accuracy,
                evasion,
                DamageBreakdown.empty()
        );
    }

    public DamageResult(double finalDamage) {
        this(finalDamage, false);
    }

    /**
     * HP ダメージのみの結果を作成します。
     *
     * @param finalDamage HP に適用する最終ダメージ
     * @param critical    通常会心が成立したか
     */
    public DamageResult(double finalDamage, boolean critical) {
        this(finalDamage, 0.0D, false, critical, false, false, 100.0D, 100.0D, 0.0D, DamageBreakdown.empty());
    }

    /**
     * 命中情報を含む HP ダメージ結果を作成します。
     *
     * @param finalDamage HP に適用する最終ダメージ
     * @param critical    通常会心が成立したか
     * @param hitChance   最終命中率
     * @param accuracy    攻撃者の命中率
     * @param evasion     被弾者の回避率
     */
    public DamageResult(
            double finalDamage,
            boolean critical,
            double hitChance,
            double accuracy,
            double evasion
    ) {
        this(finalDamage, 0.0D, false, critical, false, false, hitChance, accuracy, evasion, DamageBreakdown.empty());
    }

    /**
     * 命中・通常会心・超星会心情報を含む HP ダメージ結果を作成します。
     *
     * @param finalDamage HP に適用する最終ダメージ
     * @param critical 通常会心が成立したか
     * @param superStarCritical 超星会心が成立したか
     * @param hitChance 最終命中率
     * @param accuracy 攻撃者の命中率
     * @param evasion 被弾者の回避率
     */
    public DamageResult(
            double finalDamage,
            boolean critical,
            boolean superStarCritical,
            double hitChance,
            double accuracy,
            double evasion
    ) {
        this(
                finalDamage,
                0.0D,
                false,
                critical,
                superStarCritical,
                false,
                hitChance,
                accuracy,
                evasion,
                DamageBreakdown.empty()
        );
    }

    /**
     * 中間計算値を含む HP ダメージ結果を作成します。
     *
     * @param finalDamage HP に適用する最終ダメージ
     * @param critical    通常会心が成立したか
     * @param hitChance   最終命中率
     * @param accuracy    攻撃者の命中率
     * @param evasion     被弾者の回避率
     * @param breakdown   計算時点の中間値
     */
    public DamageResult(
            double finalDamage,
            boolean critical,
            double hitChance,
            double accuracy,
            double evasion,
            DamageBreakdown breakdown
    ) {
        this(finalDamage, 0.0D, false, critical, false, false, hitChance, accuracy, evasion, breakdown);
    }

    /**
     * 通常会心・超星会心と中間計算値を含む HP ダメージ結果を作成します。
     *
     * @param finalDamage      HP に適用する最終ダメージ
     * @param critical         通常会心が成立したか
     * @param superStarCritical 超星会心が成立したか
     * @param hitChance        最終命中率
     * @param accuracy         攻撃者の命中率
     * @param evasion          被弾者の回避率
     * @param breakdown        計算時点の中間値
     */
    public DamageResult(
            double finalDamage,
            boolean critical,
            boolean superStarCritical,
            double hitChance,
            double accuracy,
            double evasion,
            DamageBreakdown breakdown
    ) {
        this(
                finalDamage,
                0.0D,
                false,
                critical,
                superStarCritical,
                false,
                hitChance,
                accuracy,
                evasion,
                breakdown
        );
    }

    /**
     * 回避されたダメージ結果を作成します。
     *
     * @param hitChance 最終命中率
     * @param accuracy  攻撃者の命中率
     * @param evasion   被弾者の回避率
     * @return 回避結果
     */
    public static DamageResult evaded(double hitChance, double accuracy, double evasion) {
        return new DamageResult(
                0.0D,
                0.0D,
                false,
                false,
                false,
                true,
                hitChance,
                accuracy,
                evasion,
                DamageBreakdown.empty()
        );
    }

    /**
     * シールドだけへ適用するダメージ結果を作成します。
     *
     * @param shieldDamage シールドに適用するダメージ
     * @param shieldBroken この結果でシールドを破壊したか
     * @return シールドダメージ結果
     */
    public static DamageResult shield(double shieldDamage, boolean shieldBroken) {
        return shield(shieldDamage, shieldBroken, false);
    }

    /**
     * シールドだけへ適用するダメージ結果を作成します。
     *
     * @param shieldDamage シールドに適用するダメージ
     * @param shieldBroken この結果でシールドを破壊したか
     * @param critical     通常会心が成立したか
     * @return シールドダメージ結果
     */
    public static DamageResult shield(double shieldDamage, boolean shieldBroken, boolean critical) {
        return new DamageResult(
                0.0D,
                shieldDamage,
                shieldBroken,
                critical,
                false,
                false,
                100.0D,
                100.0D,
                0.0D,
                DamageBreakdown.empty()
        );
    }

    /**
     * 元の命中・会心情報を保持したシールドダメージ結果を作成します。
     *
     * @param shieldDamage シールドに適用するダメージ
     * @param shieldBroken この結果でシールドを破壊したか
     * @param source       シールド適用前のダメージ結果
     * @return シールドダメージ結果
     */
    public static DamageResult shield(double shieldDamage, boolean shieldBroken, DamageResult source) {
        return new DamageResult(
                0.0D,
                shieldDamage,
                shieldBroken,
                source.critical(),
                source.superStarCritical(),
                source.evaded(),
                source.hitChance(),
                source.accuracy(),
                source.evasion(),
                source.breakdown()
        );
    }

    /**
     * 命中・通常会心・超星会心情報を保ったまま最終 HP ダメージを差し替えます。
     *
     * @param newFinalDamage 差し替える最終ダメージ
     * @return 差し替え後の結果
     */
    public DamageResult withFinalDamage(double newFinalDamage) {
        return new DamageResult(
                newFinalDamage,
                shieldDamage,
                shieldBroken,
                critical,
                superStarCritical,
                evaded,
                hitChance,
                accuracy,
                evasion,
                breakdown
        );
    }
}
