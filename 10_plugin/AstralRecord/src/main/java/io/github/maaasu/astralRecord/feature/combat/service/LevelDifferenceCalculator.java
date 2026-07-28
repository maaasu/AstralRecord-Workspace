package io.github.maaasu.astralRecord.feature.combat.service;

/**
 * プレイヤーと Mob のレベル差に応じた撃破経験値補正を計算します。
 */
public final class LevelDifferenceCalculator {

    private static final double EXPERIENCE_PENALTY_PER_LEVEL = 0.05D;
    private static final double MIN_EXPERIENCE_MULTIPLIER = 0.10D;

    private LevelDifferenceCalculator() {
    }

    /**
     * プレイヤーと撃破 Mob のレベル差から経験値倍率を計算します。
     *
     * <p>レベル差 1 ごとに 5% 減衰し、最低 10% を維持します。プレイヤーが
     * Mob より高い場合も低い場合も、絶対値のレベル差だけを使用します。</p>
     *
     * @param playerLevel プレイヤーレベル
     * @param mobLevel    撃破 Mob のレベル
     * @return 経験値倍率
     */
    public static double experienceMultiplier(int playerLevel, int mobLevel) {
        int gap = Math.abs(normalize(playerLevel) - normalize(mobLevel));
        return Math.max(MIN_EXPERIENCE_MULTIPLIER, 1.0D - gap * EXPERIENCE_PENALTY_PER_LEVEL);
    }

    /**
     * レベル差補正後の経験値を計算します。
     *
     * @param baseExperience Mob 定義上の基礎経験値
     * @param playerLevel    プレイヤーレベル
     * @param mobLevel       撃破 Mob のレベル
     * @return 補正後の経験値。基礎経験値が正の場合は最低 1
     */
    public static int scaleExperience(int baseExperience, int playerLevel, int mobLevel) {
        if (baseExperience <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(baseExperience * experienceMultiplier(playerLevel, mobLevel)));
    }

    private static int normalize(int level) {
        return Math.max(1, level);
    }
}
