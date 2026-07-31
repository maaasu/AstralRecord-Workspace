package io.github.maaasu.astralRecord.feature.mob.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Mob が持つシールド設定です。
 *
 * @param enabled シールドを有効にするか
 * @param max     スポーン時のシールド値
 * @param rechargeTimeSeconds 破壊から一括回復までの基礎秒数。未設定時はリチャージしない
 * @param rechargeAmount 完了時の回復量。未設定時は {@code max}
 */
public record MobShieldConfig(
        boolean enabled,
        double max,
        @Nullable Double rechargeTimeSeconds,
        @Nullable Double rechargeAmount
) {

    public static final MobShieldConfig EMPTY = new MobShieldConfig(false, 0.0D, null, null);

    /**
     * リチャージを持たない従来形式の設定を作成します。
     *
     * @param enabled シールドを有効にするか
     * @param max スポーン時のシールド値
     */
    public MobShieldConfig(boolean enabled, double max) {
        this(enabled, max, null, null);
    }

    /**
     * 有効かつ最大値が 0 より大きいシールド設定かを返します。
     *
     * @return シールドを持つ場合は true
     */
    public boolean active() {
        return enabled && max > 0.0D;
    }

    /**
     * 最大シールド値を 0 以上へ正規化した設定を返します。
     *
     * @return 正規化済み設定
     */
    public @NotNull MobShieldConfig normalized() {
        Double normalizedTime = rechargeTimeSeconds == null ? null : Math.max(0.0D, rechargeTimeSeconds);
        Double normalizedAmount = rechargeAmount == null ? null : Math.max(0.0D, rechargeAmount);
        return new MobShieldConfig(enabled, Math.max(0.0D, max), normalizedTime, normalizedAmount);
    }

    /**
     * 破壊後にリチャージを開始できる設定か返します。
     *
     * @return 回復時間が明示されている場合は {@code true}
     */
    public boolean rechargeable() {
        return active() && rechargeTimeSeconds != null;
    }

    /**
     * 完了時に設定するシールド値を返します。
     *
     * @return 明示回復量、未設定時はスポーン時シールド値
     */
    public double resolvedRechargeAmount() {
        return rechargeAmount == null ? max : rechargeAmount;
    }
}
