package io.github.maaasu.astralarchitect.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * config.ymlから読み込んだ実行時設定です。
 */
public record ArchitectConfig(
        long maxBlockCount,
        int targetBlockDistance,
        long maxChangedBlockCount,
        Set<String> forbiddenBlockTypes,
        Duration trashRetention) {

    /**
     * プラグイン設定を検証して読み込みます。
     *
     * @param plugin 設定を所有するプラグイン
     * @return 検証済み設定
     * @throws IllegalArgumentException 数値設定が不正な場合
     */
    public static ArchitectConfig load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        long maxBlockCount = config.getLong("selection.max-block-count", 262_144L);
        int targetDistance = config.getInt("selection.target-block-distance", 100);
        long maxChangedBlockCount = config.getLong("candidate.max-changed-block-count", 262_144L);
        int retentionDays = config.getInt("tickets.trash-retention-days", 7);

        if (maxBlockCount <= 0L) {
            throw new IllegalArgumentException("selection.max-block-count must be positive");
        }
        if (targetDistance <= 0) {
            throw new IllegalArgumentException("selection.target-block-distance must be positive");
        }
        if (maxChangedBlockCount <= 0L) {
            throw new IllegalArgumentException("candidate.max-changed-block-count must be positive");
        }
        if (retentionDays < 0) {
            throw new IllegalArgumentException("tickets.trash-retention-days must not be negative");
        }

        Set<String> forbidden = config.getStringList("candidate.forbidden-block-types").stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        return new ArchitectConfig(
                maxBlockCount,
                targetDistance,
                maxChangedBlockCount,
                forbidden,
                Duration.ofDays(retentionDays));
    }
}
