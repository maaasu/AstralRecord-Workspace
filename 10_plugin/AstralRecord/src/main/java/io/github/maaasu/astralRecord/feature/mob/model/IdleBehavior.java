package io.github.maaasu.astralRecord.feature.mob.model;

/**
 * Mob の非接敵時の行動パターン。
 */
public enum IdleBehavior {

    /** その場から動かない */
    STATIONARY,

    /** スポーン地点を中心にランダムに徘徊する */
    WANDER,

    /** プラグイン側で定義された経路を巡回する（将来拡張。当面は STATIONARY と同じ扱い） */
    PATROL;

    /**
     * 文字列から行動パターンを解決します。未知値は {@link #STATIONARY} にフォールバックします。
     *
     * @param value 行動パターン文字列（大小区別なし）
     * @return 解決された行動パターン
     */
    public static IdleBehavior from(String value) {
        if (value == null || value.isBlank()) {
            return STATIONARY;
        }
        try {
            return IdleBehavior.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return STATIONARY;
        }
    }
}
