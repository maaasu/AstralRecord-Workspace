package io.github.maaasu.astralRecord.feature.world.config;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.world.model.JoinSpawnLocation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * plugin.yml に定義された参加時スポーン設定を読み取ります。
 */
public final class PluginJoinSpawnConfig {

    private PluginJoinSpawnConfig() {
    }

    /**
     * plugin.yml から参加時スポーン設定を読み取ります。
     *
     * @param plugin プラグイン
     * @return 参加時スポーン設定
     */
    @NotNull
    public static JoinSpawnLocation load(@NotNull AstralRecord plugin) {
        try (var input = plugin.getResource("plugin.yml")) {
            if (input == null) {
                return defaultLocation();
            }

            var yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8)
            );
            String prefix = "joinSpawn.";
            return new JoinSpawnLocation(
                    yaml.getString(prefix + "world", "world"),
                    yaml.getDouble(prefix + "x", 0.5D),
                    yaml.getDouble(prefix + "y", 64.0D),
                    yaml.getDouble(prefix + "z", 0.5D),
                    (float) yaml.getDouble(prefix + "yaw", 0.0D),
                    (float) yaml.getDouble(prefix + "pitch", 0.0D)
            );
        } catch (Exception ignored) {
            return defaultLocation();
        }
    }

    @NotNull
    private static JoinSpawnLocation defaultLocation() {
        return new JoinSpawnLocation("world", 0.5D, 64.0D, 0.5D, 0.0F, 0.0F);
    }
}
