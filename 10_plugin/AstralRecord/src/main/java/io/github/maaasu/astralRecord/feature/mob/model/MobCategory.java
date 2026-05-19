package io.github.maaasu.astralRecord.feature.mob.model;

/**
 * Mobテンプレートのカテゴリ。
 */
public enum MobCategory {
    ENEMY,
    BOSS,
    NPC;

    /**
     * 文字列からカテゴリを解決します。
     *
     * @param value カテゴリ文字列
     * @return 解決されたカテゴリ
     */
    public static MobCategory from(String value) {
        if (value == null || value.isBlank()) {
            return ENEMY;
        }
        return MobCategory.valueOf(value.trim().toUpperCase());
    }
}
