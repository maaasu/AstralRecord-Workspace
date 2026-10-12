package io.github.maaasu.astralRecord.feature.serverrecord.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

/** @param refreshIntervalSeconds 更新間隔（秒） @param limit 各ランキングの表示人数 */
public record ServerRecordConfig(long refreshIntervalSeconds, int limit) {
    public static final long DEFAULT_REFRESH_INTERVAL_SECONDS = 30L;

    /**
     * 未設定時も動作する設定を読みます。不正な間隔は30秒、人数は1～20へ補正します。
     * @param config 読込済み設定
     * @return 表示設定
     */
    public static @NotNull ServerRecordConfig load(@NotNull FileConfiguration config) {
        long interval = config.getLong("serverRecord.refreshIntervalSeconds", DEFAULT_REFRESH_INTERVAL_SECONDS);
        if (interval < 5L || interval > 86_400L) interval = DEFAULT_REFRESH_INTERVAL_SECONDS;
        return new ServerRecordConfig(interval, Math.max(1, Math.min(20, config.getInt("serverRecord.limit", 10))));
    }
}
