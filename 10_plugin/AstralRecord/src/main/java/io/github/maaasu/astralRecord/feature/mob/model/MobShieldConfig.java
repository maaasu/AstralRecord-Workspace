package io.github.maaasu.astralRecord.feature.mob.model;

import org.jetbrains.annotations.NotNull;

/**
 * Mob が持つシールド設定です。
 *
 * @param enabled シールドを有効にするか
 * @param max     最大シールド値
 */
public record MobShieldConfig(boolean enabled, double max) {

    public static final MobShieldConfig EMPTY = new MobShieldConfig(false, 0.0D);

    /**
     * 有効かつ最大値が 0 より大きいシールド設定かを返します。
     *
     * @return シールドを持つ場合は true
     */
    public boolean active() {
        return enabled && max > 0.0D;
    }

    /**
     * 最大シールド値を 0 以上へ正規化した設定を返します。
     *
     * @return 正規化済み設定
     */
    public @NotNull MobShieldConfig normalized() {
        return new MobShieldConfig(enabled, Math.max(0.0D, max));
    }
}
