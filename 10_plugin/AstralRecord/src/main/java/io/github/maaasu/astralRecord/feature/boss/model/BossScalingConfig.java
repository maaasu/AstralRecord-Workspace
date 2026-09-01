package io.github.maaasu.astralRecord.feature.boss.model;

/**
 * ボス挑戦における与ダメージ倍率の設定です。
 *
 * <p>HP・シールドは {@code MobCategory.BOSS} 共通の固定補正を使用します。</p>
 */
public record BossScalingConfig(
        boolean enabled,
        double attackPerExtraPlayer
) {
    public static final BossScalingConfig EMPTY = new BossScalingConfig(false, 0.0D);
}
