package io.github.maaasu.astralRecord.feature.mob.model;

/**
 * Mob の戦闘スタイル。攻撃力スケーリングと使用ステータスを決定する。
 */
public enum CombatStyle {

    /** 近接戦闘。STRENGTH でスケール、物理ダメージ */
    MELEE,

    /** 遠距離物理戦闘。DEXTERITY でスケール、物理ダメージ */
    RANGED,

    /** 魔法戦闘。INTELLIGENCE でスケール、魔法ダメージ */
    MAGIC;

    /**
     * 文字列から戦闘スタイルを解決します。未知値は {@link #MELEE} にフォールバックします。
     *
     * @param value スタイル文字列（大小区別なし）
     * @return 解決された戦闘スタイル
     */
    public static CombatStyle from(String value) {
        if (value == null || value.isBlank()) {
            return MELEE;
        }
        try {
            return CombatStyle.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return MELEE;
        }
    }
}
