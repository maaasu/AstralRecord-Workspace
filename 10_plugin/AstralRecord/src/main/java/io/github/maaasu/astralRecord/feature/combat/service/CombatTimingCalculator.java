package io.github.maaasu.astralRecord.feature.combat.service;

/**
 * 戦闘アクションの時間をステータスから解決します。
 */
public final class CombatTimingCalculator {

    private static final double BASE_ATTACK_SPEED = 100.0D;
    public static final double MAX_TIME_REDUCTION_PERCENT = 50.0D;

    private CombatTimingCalculator() {
    }

    /**
     * クールダウン短縮率を適用した tick 数を返します。
     *
     * @param baseTicks 基本クールダウン tick
     * @param reduction クールダウン短縮率（%）
     * @return 短縮後クールダウン tick
     */
    public static long resolveCooldownTicks(long baseTicks, double reduction) {
        if (baseTicks <= 0L) {
            return 0L;
        }
        double multiplier = 1.0D - normalizeTimeReduction(reduction) / 100.0D;
        return Math.max(0L, (long) Math.ceil(baseTicks * multiplier));
    }

    /**
     * 複数経路の詠唱時間短縮を乗算し、合計短縮率を50%以内へ制限した倍率を返します。
     *
     * @param primaryReduction ステータス由来の短縮率（%）
     * @param additionalReduction パッシブなど追加経路の短縮率（%）
     * @return 基礎詠唱時間へ乗算する0.5以上1.0以下の倍率
     */
    public static double resolveStackedTimeReductionMultiplier(
        double primaryReduction,
        double additionalReduction
    ) {
        double primaryMultiplier = 1.0D - normalizeTimeReduction(primaryReduction) / 100.0D;
        double additionalMultiplier = 1.0D - normalizeTimeReduction(additionalReduction) / 100.0D;
        double minimumMultiplier = 1.0D - MAX_TIME_REDUCTION_PERCENT / 100.0D;
        return Math.max(minimumMultiplier, primaryMultiplier * additionalMultiplier);
    }

    /**
     * 攻撃速度を適用した通常攻撃・攻撃行動の間隔を返します。
     *
     * @param baseTicks 基本攻撃間隔 tick
     * @param attackSpeed 攻撃速度。100 を基準値とする
     * @return 調整後攻撃間隔 tick。正の基本間隔は最低 1 tick
     */
    public static long resolveAttackIntervalTicks(long baseTicks, double attackSpeed) {
        if (baseTicks <= 0L) {
            return 0L;
        }
        double effectiveAttackSpeed = Double.isFinite(attackSpeed) && attackSpeed > 0.0D
                ? attackSpeed
                : BASE_ATTACK_SPEED;
        return Math.max(1L, (long) Math.ceil(baseTicks * BASE_ATTACK_SPEED / effectiveAttackSpeed));
    }

    private static double normalizeTimeReduction(double reduction) {
        if (!Double.isFinite(reduction)) {
            return 0.0D;
        }
        return Math.clamp(reduction, 0.0D, MAX_TIME_REDUCTION_PERCENT);
    }
}
