package io.github.maaasu.astralRecord.feature.combat.model;

/**
 * ダメージの属性を表します。
 */
public enum DamageElement {
    FIRE,
    ICE,
    POISON,
    LIGHTNING,
    HOLY,
    DARK,
    NEUTRAL;

    /**
     * 文字列から属性を解決します。
     *
     * @param raw 入力文字列。null または不正値の場合は NEUTRAL
     * @return 解決した属性
     */
    public static DamageElement from(Object raw) {
        if (raw == null) {
            return NEUTRAL;
        }
        try {
            return DamageElement.valueOf(raw.toString().trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return NEUTRAL;
        }
    }
}
