package io.github.maaasu.astralRecord.feature.combat.model;

/**
 * ダメージ計算結果を表します。
 *
 * @param finalDamage  HP に適用する最終ダメージ
 * @param shieldDamage シールドに適用するダメージ
 * @param shieldBroken この結果でシールドを破壊したか
 * @param critical     会心または超会心が成立したか
 */
public record DamageResult(double finalDamage, double shieldDamage, boolean shieldBroken, boolean critical) {

    public DamageResult(double finalDamage) {
        this(finalDamage, false);
    }

    /**
     * HP ダメージのみの結果を作成します。
     *
     * @param finalDamage HP に適用する最終ダメージ
     * @param critical    会心または超会心が成立したか
     */
    public DamageResult(double finalDamage, boolean critical) {
        this(finalDamage, 0.0D, false, critical);
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
     * @param critical     会心または超会心が成立したか
     * @return シールドダメージ結果
     */
    public static DamageResult shield(double shieldDamage, boolean shieldBroken, boolean critical) {
        return new DamageResult(0.0D, shieldDamage, shieldBroken, critical);
    }
}
