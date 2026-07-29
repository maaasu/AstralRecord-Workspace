package io.github.maaasu.astralRecord.feature.combat.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * ダメージ計算時に実際に参照・算出した中間値を保持します。
 *
 * @param resolvedAttackPower scaling と外部 base を解決した攻撃力
 * @param rawDefense          貫通適用前の全体防御力と種別防御力の合計
 * @param effectiveDefense    各防御力へ対応する貫通を適用した有効防御力の合計
 * @param elementResistances  属性ごとの計算時耐性
 */
public record DamageBreakdown(
        double resolvedAttackPower,
        double rawDefense,
        double effectiveDefense,
        @NotNull List<ElementResistance> elementResistances
) {

    public DamageBreakdown {
        elementResistances = List.copyOf(elementResistances);
    }

    /**
     * 中間値を持たない既定値を返します。
     *
     * @return 全項目が空の中間計算値
     */
    public static @NotNull DamageBreakdown empty() {
        return new DamageBreakdown(0.0D, 0.0D, 0.0D, List.of());
    }

    /**
     * 1属性に対して実際に参照した耐性値を保持します。
     *
     * @param element             対象属性
     * @param rawResistance       上限・貫通適用前の耐性
     * @param effectiveResistance 耐性上限と属性貫通を適用した実効耐性
     */
    public record ElementResistance(
            @NotNull DamageElement element,
            double rawResistance,
            double effectiveResistance
    ) {
    }
}
