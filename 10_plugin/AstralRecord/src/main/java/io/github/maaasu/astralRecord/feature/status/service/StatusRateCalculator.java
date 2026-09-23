package io.github.maaasu.astralRecord.feature.status.service;

import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * ステータスの割合値を基準量へ適用します。
 */
public final class StatusRateCalculator {

    private static final double NEUTRAL_RATE_PERCENT = 100.0D;

    private StatusRateCalculator() {
    }

    /**
     * スナップショットから割合ステータスを取得します。
     *
     * <p>スナップショットまたはステータス値が存在しない場合は、倍率の基準値である100%を返します。</p>
     *
     * @param snapshot 対象ステータスのスナップショット
     * @param type 割合ステータス種別
     * @return 抽選済みの割合値。未初期化状態では100%
     */
    public static double resolveRatePercent(
        @Nullable StatusSnapshot snapshot,
        @NotNull StatusType type
    ) {
        if (snapshot == null || snapshot.getValue(type) == null) {
            return NEUTRAL_RATE_PERCENT;
        }
        double value = snapshot.rollValue(type);
        return Double.isFinite(value) ? value : NEUTRAL_RATE_PERCENT;
    }

    /**
     * 整数の基準量へ割合ステータスを適用し、最も近い整数へ丸めます。
     *
     * @param snapshot 対象ステータスのスナップショット
     * @param type 適用する割合ステータス種別
     * @param baseAmount 補正前の基準量
     * @return 補正後の非負整数。入力が0以下なら0
     */
    public static int applyRate(
        @Nullable StatusSnapshot snapshot,
        @NotNull StatusType type,
        int baseAmount
    ) {
        if (baseAmount <= 0) {
            return 0;
        }
        double ratePercent = Math.max(0.0D, resolveRatePercent(snapshot, type));
        double adjustedAmount = baseAmount * ratePercent / NEUTRAL_RATE_PERCENT;
        if (!Double.isFinite(adjustedAmount) || adjustedAmount >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.round(adjustedAmount);
    }
}
