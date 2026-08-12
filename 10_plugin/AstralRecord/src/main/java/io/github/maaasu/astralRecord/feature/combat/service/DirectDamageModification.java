package io.github.maaasu.astralRecord.feature.combat.service;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** 直接攻撃へ適用する倍率と、元攻撃の反映完了後に実行する処理を保持します。 */
public record DirectDamageModification(
        double damageMultiplier,
        @NotNull Runnable afterDamageApplied
) {

    private static final Runnable NO_OP = () -> { };
    private static final DirectDamageModification NONE = new DirectDamageModification(1.0D, NO_OP);

    /**
     * 倍率と後処理を正規化します。
     *
     * @param damageMultiplier 直接攻撃へ適用する0以上の倍率
     * @param afterDamageApplied 元攻撃のshield・HP・死亡処理・表示完了後に実行する処理
     */
    public DirectDamageModification {
        damageMultiplier = Math.max(0.0D, damageMultiplier);
        afterDamageApplied = Objects.requireNonNull(afterDamageApplied, "afterDamageApplied");
    }

    /**
     * 倍率変更も後処理も行わない結果を返します。
     *
     * @return 変更なしの結果
     */
    public static @NotNull DirectDamageModification none() {
        return NONE;
    }

    /**
     * 後処理なしで倍率だけを適用する結果を返します。
     *
     * @param damageMultiplier 直接攻撃へ適用する0以上の倍率
     * @return 倍率変更結果
     */
    public static @NotNull DirectDamageModification multiplier(double damageMultiplier) {
        return new DirectDamageModification(damageMultiplier, NO_OP);
    }
}
