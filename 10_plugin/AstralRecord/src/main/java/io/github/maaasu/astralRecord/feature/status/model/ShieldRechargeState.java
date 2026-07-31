package io.github.maaasu.astralRecord.feature.status.model;

/**
 * シールド破壊後のリチャージ進行を表すセッション内状態です。
 *
 * @param startedAtMs リチャージを開始した時刻（epoch milliseconds）
 * @param completesAtMs リチャージが完了する予定時刻（epoch milliseconds）
 * @param rechargeAmount 完了時に一括設定するシールド値
 */
public record ShieldRechargeState(
        long startedAtMs,
        long completesAtMs,
        double rechargeAmount
) {

    /**
     * 指定時刻における残り時間を返します。
     *
     * @param nowMs 判定時刻（epoch milliseconds）
     * @return 0 以上の残りミリ秒
     */
    public long remainingMs(long nowMs) {
        return Math.max(0L, completesAtMs - nowMs);
    }

    /**
     * 指定時刻における進捗率を返します。
     *
     * @param nowMs 判定時刻（epoch milliseconds）
     * @return 0.0 以上 1.0 以下の進捗率
     */
    public double progress(long nowMs) {
        long durationMs = Math.max(0L, completesAtMs - startedAtMs);
        if (durationMs == 0L) {
            return 1.0D;
        }
        return Math.clamp((double) (nowMs - startedAtMs) / (double) durationMs, 0.0D, 1.0D);
    }

    /**
     * 完了予定時刻へ待機時間を追加した新しい状態を返します。
     *
     * @param additionalMs 追加するミリ秒。0 以下は無視する
     * @return 延長後の状態
     */
    public ShieldRechargeState extendedBy(long additionalMs) {
        if (additionalMs <= 0L) {
            return this;
        }
        long extendedAt;
        try {
            extendedAt = Math.addExact(completesAtMs, additionalMs);
        } catch (ArithmeticException ignored) {
            extendedAt = Long.MAX_VALUE;
        }
        return new ShieldRechargeState(startedAtMs, extendedAt, rechargeAmount);
    }
}
