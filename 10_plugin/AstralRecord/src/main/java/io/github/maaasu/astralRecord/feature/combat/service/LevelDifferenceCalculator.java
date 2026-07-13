package io.github.maaasu.astralRecord.feature.combat.service;

/**
 * プレイヤーと Mob のレベル差に応じた戦闘・経験値補正を計算します。
 *
 * <p>レベル差補正は、Mob の {@code baseStats} やプレイヤーのステータス補正を
 * 書き換えず、各ダメージ・経験値の最終値へ適用します。</p>
 */
public final class LevelDifferenceCalculator {

    private static final double DAMAGE_PER_LEVEL = 0.03D;
    private static final double MIN_DAMAGE_MULTIPLIER = 0.70D;
    private static final double MAX_DAMAGE_MULTIPLIER = 1.30D;
    private static final double EXPERIENCE_PENALTY_PER_LEVEL = 0.05D;
    private static final double MIN_EXPERIENCE_MULTIPLIER = 0.10D;

    private LevelDifferenceCalculator() {
    }

    /**
     * 攻撃者と防御者のレベル差からダメージ倍率を計算します。
     *
     * <p>同レベルは 1.0 倍、攻撃者が 10 レベル高い場合は 1.3 倍、
     * 10 レベル低い場合は 0.7 倍です。倍率は 0.7 倍から 1.3 倍に制限します。</p>
     *
     * @param attackerLevel 攻撃者レベル
     * @param defenderLevel 防御者レベル
     * @return レベル差を反映したダメージ倍率
     */
    public static double damageMultiplier(int attackerLevel, int defenderLevel) {
        int gap = normalize(attackerLevel) - normalize(defenderLevel);
        return clamp(1.0D + gap * DAMAGE_PER_LEVEL, MIN_DAMAGE_MULTIPLIER, MAX_DAMAGE_MULTIPLIER);
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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
