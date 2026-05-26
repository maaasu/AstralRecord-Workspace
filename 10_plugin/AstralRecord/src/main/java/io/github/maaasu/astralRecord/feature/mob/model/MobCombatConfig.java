package io.github.maaasu.astralRecord.feature.mob.model;

import java.util.List;

/**
 * Mob の戦闘設定。{@code NPC} カテゴリでは {@code null} となる。
 *
 * @param style               戦闘スタイル
 * @param preferredRange      戦闘時の理想距離（ブロック単位）
 * @param attackIntervalTicks 通常攻撃の間隔（tick）
 * @param skills              使用スキル ID（prefix 除去済み）
 */
public record MobCombatConfig(
        CombatStyle style,
        double preferredRange,
        long attackIntervalTicks,
        List<String> skills
) {

    /**
     * skills を変更不可リストとして保持するため、コンパクトコンストラクタで防御的コピーを取る。
     */
    public MobCombatConfig {
        skills = skills == null ? List.of() : List.copyOf(skills);
    }
}
