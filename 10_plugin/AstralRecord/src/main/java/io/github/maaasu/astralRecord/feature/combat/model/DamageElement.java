package io.github.maaasu.astralRecord.feature.combat.model;

/**
 * ダメージの属性を表します。
 */
public enum DamageElement {
    NONE,
    FIRE,
    ICE,
    LIGHTNING,
    ;

    /**
     * 文字列から属性を解決します。
     *
     * 旧値 {@code NEUTRAL} は {@link #NONE} として扱います。廃止済み属性を含む不正な値も {@link #NONE} として扱います。
     *
     * @param raw 入力文字列。null または不正値の場合は NONE
     * @return 解決した属性
     */
    public static DamageElement from(Object raw) {
        if (raw == null) {
            return NONE;
        }
        String normalized = raw.toString().trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.equals("NEUTRAL")) {
            return NONE;
        }
        try {
            return DamageElement.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return NONE;
        }
    }
}
