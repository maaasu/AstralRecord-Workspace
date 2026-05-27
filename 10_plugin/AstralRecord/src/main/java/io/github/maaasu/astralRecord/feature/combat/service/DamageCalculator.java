package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.DamageContext;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import org.jetbrains.annotations.NotNull;

/**
 * ダメージ計算の純粋ロジックを担うクラスです。
 * <p>
 * Bukkit API への依存を持たず、{@link DamageContext} の入力値のみから
 * {@link DamageResult} を導出します。攻撃力／防御力／会心／属性補正等は
 * 将来この実装内で拡張する想定で、現状は下限クランプのみを行います。
 */
public final class DamageCalculator {

    /**
     * ダメージを計算し、結果を返します。
     * <p>
     * 攻撃力・防御力・クリティカル・属性補正などはここで計算します。
     * 最終ダメージは必ず 0 以上にクランプします。
     *
     * @param context ダメージ計算入力
     * @return 計算結果
     */
    public @NotNull DamageResult calculate(@NotNull DamageContext context) {
        double damage = context.baseDamage();

        // 攻撃力、防御力、クリティカル、属性補正などをここで計算
        damage = Math.max(0.0D, damage);

        return new DamageResult(damage);
    }
}
