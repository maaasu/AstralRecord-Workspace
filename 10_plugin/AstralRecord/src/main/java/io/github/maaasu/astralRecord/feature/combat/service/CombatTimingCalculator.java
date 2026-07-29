package io.github.maaasu.astralRecord.feature.combat.service;

/**
 * 戦闘アクションの時間をステータスから解決します。
 */
public final class CombatTimingCalculator {

    private static final double BASE_ATTACK_SPEED = 100.0D;

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
        double multiplier = Math.max(0.0D, 1.0D - Math.max(0.0D, reduction) / 100.0D);
        return Math.max(0L, (long) Math.ceil(baseTicks * multiplier));
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
}
