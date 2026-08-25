package io.github.maaasu.astralRecord.feature.mob.model;

/**
 * Mob の戦闘設定。{@code NPC} カテゴリでは {@code null} となる。
 *
 * @param style               戦闘スタイル
 * @param preferredRange      戦闘時の理想距離（ブロック単位）
 * @param normalAttack        組み込み通常攻撃。{@code null} なら通常攻撃なし
 * @param skills              Mob 専用スキルの使用順
 */
public record MobCombatConfig(
        CombatStyle style,
        double preferredRange,
        MobNormalAttackConfig normalAttack,
        java.util.List<MobSkillBinding> skills
) {

    /**
     * 旧形式の通常攻撃設定から構築します。
     *
     * @param style               戦闘スタイル
     * @param preferredRange      戦闘時の理想距離と通常攻撃距離
     * @param attackIntervalTicks 通常攻撃の間隔
     */
    public MobCombatConfig(CombatStyle style, double preferredRange, long attackIntervalTicks) {
        this(style, preferredRange, new MobNormalAttackConfig(preferredRange, attackIntervalTicks), java.util.List.of());
    }

    /** 不変なスキル一覧と安全な理想距離を保持します。 */
    public MobCombatConfig {
        preferredRange = Math.max(0.0D, preferredRange);
        skills = java.util.List.copyOf(skills == null ? java.util.List.of() : skills);
    }
}
