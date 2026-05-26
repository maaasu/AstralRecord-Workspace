package io.github.maaasu.astralRecord.feature.mob.model;

/**
 * Mob のターゲット選定方式。
 */
public enum TargetStrategy {

    /** 最も近いプレイヤー */
    NEAREST,

    /** 脅威値（ヘイト）が最も高いプレイヤー */
    HIGHEST_THREAT,

    /** 範囲内のプレイヤーからランダムに選出 */
    RANDOM,

    /** 現在 HP が最低のプレイヤーを優先 */
    LOWEST_HP;

    /**
     * 文字列からターゲット戦略を解決します。未知値は {@link #NEAREST} にフォールバックします。
     *
     * @param value 戦略文字列（大小区別なし）
     * @return 解決された戦略
     */
    public static TargetStrategy from(String value) {
        if (value == null || value.isBlank()) {
            return NEAREST;
        }
        try {
            return TargetStrategy.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return NEAREST;
        }
    }
}
