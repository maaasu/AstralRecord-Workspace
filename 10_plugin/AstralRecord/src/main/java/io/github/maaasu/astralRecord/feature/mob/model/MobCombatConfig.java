package io.github.maaasu.astralRecord.feature.mob.model;

/**
 * Mob の戦闘設定。{@code NPC} カテゴリでは {@code null} となる。
 *
 * @param style               戦闘スタイル
 * @param preferredRange      戦闘時の理想距離（ブロック単位）
 * @param attackIntervalTicks 通常攻撃の間隔（tick）
 */
public record MobCombatConfig(
        CombatStyle style,
        double preferredRange,
        long attackIntervalTicks
) { }
