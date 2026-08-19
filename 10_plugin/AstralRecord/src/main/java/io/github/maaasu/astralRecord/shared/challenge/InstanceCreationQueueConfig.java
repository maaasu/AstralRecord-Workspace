package io.github.maaasu.astralRecord.shared.challenge;

import io.github.maaasu.astralRecord.infrastructure.config.ConfigKeys;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

/**
 * Boss／Dungeon のインスタンス作成枠設定を保持します。
 *
 * @param boss Boss 用の作成枠
 * @param dungeon Dungeon 用の作成枠
 */
public record InstanceCreationQueueConfig(
        @NotNull InstanceCreationLimits boss,
        @NotNull InstanceCreationLimits dungeon
) {
    /** Boss の既定作成枠です。通常2件、寄付者予約1件を保持します。 */
    public static final InstanceCreationLimits DEFAULT_BOSS = new InstanceCreationLimits(2, 1);
    /** Dungeon の既定作成枠です。通常3件、寄付者予約1件を保持します。 */
    public static final InstanceCreationLimits DEFAULT_DUNGEON = new InstanceCreationLimits(3, 1);
    public InstanceCreationQueueConfig {
        if (boss == null || dungeon == null) {
            throw new IllegalArgumentException("Instance creation limits are required");
        }
    }

    /**
     * Plugin の config.yml から作成枠設定を読み込みます。
     * 不正な数値は、通常枠を1件以上、予約枠を0件以上へ丸めます。
     *
     * @param config config.yml
     * @return 読み込んだ作成枠設定
     */
    public static @NotNull InstanceCreationQueueConfig from(@NotNull FileConfiguration config) {
        return new InstanceCreationQueueConfig(
                limits(config, ConfigKeys.INSTANCE_LIMITS_BOSS, DEFAULT_BOSS),
                limits(config, ConfigKeys.INSTANCE_LIMITS_DUNGEON, DEFAULT_DUNGEON)
        );
    }

    private static @NotNull InstanceCreationLimits limits(
            @NotNull FileConfiguration config,
            @NotNull String path,
            @NotNull InstanceCreationLimits defaults
    ) {
        return new InstanceCreationLimits(
                Math.max(1, config.getInt(path + ".normalLimit", defaults.normalLimit())),
                Math.max(0, config.getInt(path + ".reservedLimit", defaults.reservedLimit()))
        );
    }

    /** 通常枠と寄付者予約枠の上限です。 */
    public record InstanceCreationLimits(int normalLimit, int reservedLimit) {
        public InstanceCreationLimits {
            normalLimit = Math.max(1, normalLimit);
            reservedLimit = Math.max(0, reservedLimit);
        }
    }
}
