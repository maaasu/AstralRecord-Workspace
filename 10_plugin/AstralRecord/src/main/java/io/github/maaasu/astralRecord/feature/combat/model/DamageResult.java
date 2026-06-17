package io.github.maaasu.astralRecord.feature.combat.model;

/**
 * ダメージ計算結果を表します。
 *
 * @param finalDamage  HP に適用する最終ダメージ
 * @param shieldDamage シールドに適用するダメージ
 * @param shieldBroken この結果でシールドを破壊したか
 */
public record DamageResult(double finalDamage, double shieldDamage, boolean shieldBroken) {

    public DamageResult(double finalDamage) {
        this(finalDamage, 0.0D, false);
    }

    /**
     * シールドだけへ適用するダメージ結果を作成します。
     *
     * @param shieldDamage シールドに適用するダメージ
     * @param shieldBroken この結果でシールドを破壊したか
     * @return シールドダメージ結果
     */
    public static DamageResult shield(double shieldDamage, boolean shieldBroken) {
        return new DamageResult(0.0D, shieldDamage, shieldBroken);
    }
}
