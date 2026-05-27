package io.github.maaasu.astralRecord.feature.combat.model;

/**
 * ダメージ計算結果を表します。
 * <p>
 * {@code finalDamage} は防御・補正を適用した後の最終ダメージで、
 * 必ず 0 以上の値であることを呼び出し側で保証します。
 *
 * @param finalDamage 最終ダメージ（0 以上）
 */
public record DamageResult(double finalDamage) {
}
